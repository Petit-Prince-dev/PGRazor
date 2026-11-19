"""Build a PGRazor Root release for this fork without the game APK.

The fork only changes PGRazor's Java controls, so its module is the upstream release module of the same
game target with PGRazor's DEX swapped for this checkout's build. Native libraries, the payload, the
Zygisk loader and the game target come from the upstream release named in pgrazor/FORK_BASE and are
checked against that release's SHA256SUMS.txt first. The module also gets the root helper's service.sh,
the update repository and an updateJson, so phones update themselves from this fork's releases.

    py -3.12 -B tools/fork_root_release.py --tag v0.3.4-fr.1 --repo owner/PGRazor --output dist/fork

Outputs: PGRazor-Root-module.zip, update.json, root-build-info.json, SHA256SUMS.txt (and, with
--installer, PGRazor-Root.apk signed with PGRAZOR_KEYSTORE or a local development key).
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / 'pgrazor'
UPSTREAM = 'jpitty03/PGRazor'
ASSETS = ('PGRazor-Root-module.zip', 'SHA256SUMS.txt', 'root-build-info.json')
# Upstream module entries replaced or added here; everything else is kept byte for byte.
REPLACED = {'pgrazor/classes.dex', 'module.prop', 'service.sh', 'pgrazor/update-repo'}


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'PGRazor-fork-release'})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def upstream_release(base, upstream=UPSTREAM):
    """The base release's root assets, verified against its SHA256SUMS.txt."""
    files = {name: fetch(f'https://github.com/{upstream}/releases/download/{base}/{name}') for name in ASSETS}
    sums = {}
    for line in files['SHA256SUMS.txt'].decode('utf-8').splitlines():
        if '  ' in line:
            digest, name = line.split('  ', 1)
            sums[name.strip()] = digest.strip().lower()
    actual = hashlib.sha256(files['PGRazor-Root-module.zip']).hexdigest()
    if sums.get('PGRazor-Root-module.zip') != actual:
        raise SystemExit('Upstream module checksum mismatch for ' + base)
    info = json.loads(files['root-build-info.json'])
    if info.get('module_sha256') != actual:
        raise SystemExit('Upstream root-build-info.json does not match its module')
    return files['PGRazor-Root-module.zip'], info


def build_dex(tag):
    env = dict(os.environ, PGRAZOR_VERSION=tag)
    subprocess.run([sys.executable, '-B', str(PROJECT / 'build.py')], cwd=PROJECT, env=env, check=True)
    sys.path.insert(0, str(PROJECT))
    os.environ['PGRAZOR_VERSION'] = tag
    import nonroot
    return Path(nonroot.validated_build())


def fork_code(tag):
    """Module versionCode that grows with every fork build: base code * 1000 + the -fr.N number."""
    sys.path.insert(0, str(PROJECT))
    import rooted
    match = re.search(r'-fr\.([0-9]+)$', tag)
    return rooted.version_code(tag) * 1000 + (int(match.group(1)) % 1000 if match else 0)


def compose(module_bytes, dex, tag, repo, game_version):
    sys.path.insert(0, str(PROJECT))
    os.environ['PGRAZOR_UPDATE_REPO'] = repo
    import rooted
    extra = rooted.capability_module_entries()
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(module_bytes)) as source, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as target:
        names = source.namelist()
        for required in ('pgrazor/classes.dex', 'pgrazor/payload.zip', 'module.prop', 'zygisk/arm64-v8a.so'):
            if required not in names: raise SystemExit('Upstream module lacks ' + required)
        props = dict(line.split('=', 1) for line in source.read('module.prop').decode('utf-8').splitlines() if '=' in line)
        if props.get('id') != rooted.MODULE_ID: raise SystemExit('Upstream module id is not pgrazor')
        for info in source.infolist():
            if info.filename in REPLACED or info.filename in extra: continue
            compress = zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            target.writestr(zipfile.ZipInfo(info.filename, info.date_time), source.read(info), compress_type=compress)
        target.writestr('module.prop', rooted.module_prop(tag, game_version, repo, name='PGRazor Root (FR)', code=fork_code(tag)))
        target.write(dex, 'pgrazor/classes.dex')
        for name, value in extra.items():
            target.writestr(name, value)
    return out.getvalue()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--tag', required=True, help='Release tag for this fork, e.g. v0.3.4-fr.1')
    parser.add_argument('--repo', required=True, help='This fork, owner/name (phones update from its releases)')
    parser.add_argument('--base', help='Upstream release with the matching native payload (default: pgrazor/FORK_BASE)')
    parser.add_argument('--upstream', default=UPSTREAM)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/fork')
    parser.add_argument('--installer', action='store_true', help='Also build the PGRazor-Root.apk installer')
    args = parser.parse_args(argv)
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?', args.tag):
        parser.error('tag must look like v0.3.4-fr.1')
    base = args.base or (PROJECT / 'FORK_BASE').read_text(encoding='utf-8').strip()

    module_bytes, info = upstream_release(base, args.upstream)
    dex = build_dex(args.tag)
    target = info['target']
    module = compose(module_bytes, dex, args.tag, args.repo, target['versionName'])

    args.output.mkdir(parents=True, exist_ok=True)
    module_path = args.output / 'PGRazor-Root-module.zip'
    module_path.write_bytes(module)
    module_sha = hashlib.sha256(module).hexdigest()
    sums = [module_sha + '  PGRazor-Root-module.zip']
    receipt = {'version': args.tag, 'target': target, 'module_sha256': module_sha, 'abis': info.get('abis'),
               'base': base, 'dex_sha256': hashlib.sha256(dex.read_bytes()).hexdigest()}

    if args.installer:
        import rooted
        from rooted_installer import build_installer
        folder = PROJECT / 'build/fork' / args.tag
        folder.mkdir(parents=True, exist_ok=True)
        apk = build_installer(module_path, target, args.tag, folder)
        (args.output / 'PGRazor-Root.apk').write_bytes(Path(apk).read_bytes())
        receipt['installer_sha256'] = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
        sums.append(receipt['installer_sha256'] + '  PGRazor-Root.apk')

    sys.path.insert(0, str(PROJECT))
    import rooted
    download = f'https://github.com/{args.repo}/releases/download/{args.tag}'
    (args.output / 'update.json').write_text(json.dumps({
        'version': args.tag, 'versionCode': fork_code(args.tag),
        'zipUrl': download + '/PGRazor-Root-module.zip', 'changelog': download + '/changelog.md'}, indent=2) + '\n', encoding='utf-8')
    (args.output / 'changelog.md').write_text(
        f'# PGRazor Root (FR) {args.tag}\n\nBasé sur PGRazor {base}, Pokémon GO {target["versionName"]}.\n', encoding='utf-8')
    (args.output / 'root-build-info.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    (args.output / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    print('Fork release ready:', args.output, module_sha)


if __name__ == '__main__':
    main()
