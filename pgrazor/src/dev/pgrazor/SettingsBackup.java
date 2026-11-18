package dev.pgrazor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** User-selected, bounded backups. Never enumerates account or game storage. */
final class SettingsBackup {
    private static final int EXPORT=71, IMPORT=72, LIMIT=512*1024;
    private static final String[] SCOPES={"pgrazor-map","pgrazor-overlays","pgrazor-saved-places-v1","pgrazor-livefeed"};
    private static final String[][] KEYS={{"Stops","Gyms","Raids"},{"cooldown.fx","cooldown.fy","vgp.fx","vgp.fy","transfer.fx","transfer.fy","gym-feed.fx","gym-feed.fy","local-time.fx","local-time.fy"},{"places"},{"category","radius","minIv","myShinies","filters_v1","filter_presets_v1"}};
    private final Activity activity;
    private TextView status;
    private Button export,restore,back;
    private boolean busy;
    private android.app.AlertDialog dialog;
    SettingsBackup(Activity activity){this.activity=activity;}
    void show(){
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        int p=MapTheme.dp(activity,20);root.setPadding(p,p*3,p,p*2);
        TextView title=new TextView(activity);title.setText("Sauvegarde des réglages");title.setTextSize(22);root.addView(title);
        TextView detail=new TextView(activity);detail.setText("Enregistre une sauvegarde hors de l'appli pour garder tes réglages après une réinstallation. Inclut les favoris et leurs coordonnées. Les jetons de connexion, la position actuelle, l'historique de cooldown et les routes actives sont exclus.\n\nL'import remplace les réglages et favoris inclus. Le VGP reste désactivé.");detail.setPadding(0,p,0,p);root.addView(detail);
        export=new Button(activity);export.setText("Exporter les réglages");root.addView(export);
        restore=new Button(activity);restore.setText("Importer les réglages");root.addView(restore);
        status=new TextView(activity);status.setPadding(0,p,0,p);root.addView(status);
        back=new Button(activity);back.setText("Retour au jeu");back.setOnClickListener(v->{if(dialog!=null)dialog.dismiss();else activity.finish();});root.addView(back);
        export.setOnClickListener(v->pick(EXPORT));restore.setOnClickListener(v->pick(IMPORT));
        ScrollView scroll=new ScrollView(activity);scroll.addView(root);MapTheme.style(scroll);
        if(PgAssets.rooted()){
            detail.append("\n\nEn root, un fichier fixe est utilisé dans le dossier de fichiers externes du jeu. Copie-le ailleurs avant de désinstaller le jeu ; pour importer, place ta sauvegarde à cet emplacement :\n"+rootedFile());
            dialog=new android.app.AlertDialog.Builder(MapTheme.wrap(activity)).setView(scroll).create();
            dialog.setCancelable(false);dialog.show();
        }else activity.setContentView(scroll);
    }
    private File rootedFile(){File dir=activity.getExternalFilesDir(null);return dir==null?null:new File(dir,"PGRazor-settings.json");}
    private void pick(int code){
        if(PgAssets.rooted()){
            File file=rootedFile();
            if(file==null){status.setText("Le stockage externe est indisponible.");return;}
            new android.app.AlertDialog.Builder(MapTheme.wrap(activity))
                .setMessage(code==EXPORT?"Remplacer la sauvegarde des réglages dans "+file+"?":"Remplacer les réglages par la sauvegarde de "+file+" ? Le VGP restera désactivé.")
                .setNegativeButton("Annuler",null).setPositiveButton("Continuer",(d,w)->result(code,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)))).show();
            return;
        }
        Intent i=new Intent(code==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if(code==EXPORT)i.putExtra(Intent.EXTRA_TITLE,"PGRazor-settings.json");
        try{activity.startActivityForResult(i,code);}catch(android.content.ActivityNotFoundException e){status.setText("Aucun sélecteur de fichiers disponible.");}
    }
    void result(int request,int result,Intent data){
        if((request!=EXPORT&&request!=IMPORT)||result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        if(busy)return;busy=true;
        Uri uri=data.getData();export.setEnabled(false);restore.setEnabled(false);back.setEnabled(false);status.setText(request==EXPORT?"Saving backup...":"Restauration de la sauvegarde...");
        new Thread(()->{
            String message;
            try{
                if(request==EXPORT){
                    byte[] bytes=snapshot(activity).toString(2).getBytes(StandardCharsets.UTF_8);
                    if(bytes.length>LIMIT)throw new IOException("La sauvegarde est trop grosse");
                    try(OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Impossible d'ouvrir la destination");out.write(bytes);}
                    message="Réglages exportés.";
                }else{
                    JSONObject document;
                    try(InputStream in=activity.getContentResolver().openInputStream(uri)){
                        if(in==null)throw new IOException("Impossible d'ouvrir la sauvegarde");
                        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
                        while((n=in.read(buf))!=-1){if(out.size()+n>LIMIT)throw new IOException("La sauvegarde est trop grosse");out.write(buf,0,n);}
                        document=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
                    }
                    restore(activity,document);message="Réglages restaurés. Retourne au jeu pour les utiliser. Le VGP est désactivé.";
                }
            }catch(Exception e){message="Échec de la sauvegarde : "+(e.getMessage()==null?"Fichier invalide ou stockage indisponible":e.getMessage());}
            final String text=message;activity.runOnUiThread(()->{busy=false;if(activity.isFinishing()||activity.isDestroyed())return;status.setText(text);export.setEnabled(true);restore.setEnabled(true);back.setEnabled(true);});
        },"pgrazor-settings-backup").start();
    }
    boolean isBusy(){return busy;}
    private static synchronized JSONObject snapshot(Context context)throws Exception{
        JSONObject result=new JSONObject();result.put("format","PGRazor settings");result.put("version",1);result.put("speedKmh",Control.engine.speedKmh);
        JSONObject settings=new JSONObject();Properties p=Control.exportSettings();
        for(String key:SettingsBackupRules.SETTINGS)if(p.containsKey(key))settings.put(key,p.getProperty(key));result.put("settings",settings);
        JSONObject prefs=new JSONObject();for(int i=0;i<SCOPES.length;i++){
            Map<String,?> all=AccountProfiles.preferences(context,SCOPES[i]).getAll();JSONObject group=new JSONObject();
            for(String key:KEYS[i])if(all.containsKey(key))group.put(key,all.get(key));prefs.put(SCOPES[i],group);
        }result.put("preferences",prefs);SettingsBackupRules.validate(result);return result;
    }
    private static void writeGroup(Context c,String scope,JSONObject data,String[] keys)throws Exception{
        SharedPreferences.Editor edit=AccountProfiles.preferences(c,scope).edit();for(String key:keys)edit.remove(key);
        for(String key:keys)if(data.has(key)){
            Object value=data.get(key);
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Number){if(scope.equals("pgrazor-overlays"))edit.putFloat(key,((Number)value).floatValue());else edit.putInt(key,((Number)value).intValue());}
            else edit.putString(key,(String)value);
        }if(!edit.commit())throw new IOException("Impossible d'enregistrer les réglages");
    }
    private static synchronized void restore(Context context,JSONObject document)throws Exception{
        SettingsBackupRules.validate(document);JSONObject previous=snapshot(context),prefs=document.getJSONObject("preferences");
        Properties props=new Properties();JSONObject settings=document.getJSONObject("settings");for(String key:SettingsBackupRules.SETTINGS)if(settings.has(key))props.setProperty(key,settings.getString(key));
        try{
            for(int i=0;i<SCOPES.length;i++)if(prefs.has(SCOPES[i]))writeGroup(context,SCOPES[i],prefs.getJSONObject(SCOPES[i]),KEYS[i]);
            Control.importSettings((android.app.Application)context.getApplicationContext(),props,document.getDouble("speedKmh"));
        }catch(Exception error){
            JSONObject old=previous.getJSONObject("preferences");for(int i=0;i<SCOPES.length;i++)try{writeGroup(context,SCOPES[i],old.getJSONObject(SCOPES[i]),KEYS[i]);}catch(Exception rollback){error.addSuppressed(rollback);}
            throw error;
        }
    }
}
