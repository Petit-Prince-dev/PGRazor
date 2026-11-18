package dev.pgrazor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private activity declared in the host APK for user-selected log export. */
public final class LogActivity extends Activity {
    private TextView text;
    private SettingsBackup settingsBackup;
    /**
     * Opens the run log. The patched APK starts this private activity; PGRazor Root has no manifest
     * entry for it in the untouched game APK, so it shows the same content as an in-game dialog.
     */
    public static void open(Activity activity){
        if(activity==null) return;
        if(PgAssets.rooted()){ RootedLogDialog.show(activity); return; }
        activity.startActivity(new Intent(activity,LogActivity.class));
    }
    @Override public void onBackPressed(){
        if(settingsBackup!=null&&settingsBackup.isBusy())return;
        super.onBackPressed();
    }
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(getIntent().getBooleanExtra("settingsBackup",false)){
            settingsBackup=new SettingsBackup(this);settingsBackup.show();return;
        }
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(20,48,20,20);
        TextView heading = new TextView(this); heading.setText("PGRazor · session en cours"); heading.setTextSize(22); layout.addView(heading);
        ScrollView scroll = new ScrollView(this); text = new TextView(this); text.setTextIsSelectable(true); scroll.addView(text); layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button refresh = new Button(this); refresh.setText("Actualiser le log"); refresh.setOnClickListener(v -> refresh()); layout.addView(refresh);
        TextView detail=new TextView(this);detail.setText("Le ZIP contient le log et un instantané settings.json des préférences. Les jetons de connexion, données de compte et positions enregistrées sont exclus.");layout.addView(detail);
        Button bundle=new Button(this);bundle.setText("Enregistrer log + réglages (.zip)");layout.addView(bundle);
        bundle.setOnClickListener(v -> {
            File file=Control.currentLog();if(file==null)return;
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE,file.getName().replaceFirst("\\.txt$","")+".zip");startActivityForResult(intent,41);
        });
        Button export = new Button(this); export.setText("Enregistrer en .txt"); layout.addView(export);
        export.setOnClickListener(v -> {
            File file = Control.currentLog(); if(file == null) return;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE,file.getName()); startActivityForResult(intent,40);
        });
        Button back = new Button(this); back.setText("Retour aux contrôles"); back.setOnClickListener(v -> finish()); layout.addView(back);
        setContentView(layout); refresh();
    }
    private void refresh() {
        File file = Control.currentLog();
        if(file == null) { text.setText("Aucun fichier log disponible. Le logcat Android peut contenir des erreurs de démarrage."); return; }
        try (RandomAccessFile input = new RandomAccessFile(file,"r")) {
            long start = Math.max(0,input.length()-65536); input.seek(start);
            byte[] bytes = new byte[(int)(input.length()-start)]; input.readFully(bytes);
            text.setText((start>0 ? "Affichage des derniers 64 Kio. L'export enregistre le segment de log actuel, diagnostics de démarrage inclus.\n" : "") + new String(bytes,StandardCharsets.UTF_8));
        } catch(IOException error) { text.setText("Impossible de lire le log : "+error.getMessage()); }
    }
    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(settingsBackup!=null){settingsBackup.result(request,result,data);return;}
        if((request!=40&&request!=41) || result!=RESULT_OK || data==null || data.getData()==null || Control.currentLog()==null) return;
        try {
            LogExport.save(this,data.getData(),Control.currentLog(),request==41);
            Toast.makeText(this,request==41?"Log and settings saved":"Log enregistré",Toast.LENGTH_SHORT).show();
        } catch(IOException error) { Toast.makeText(this,"Échec de l'enregistrement : "+error.getMessage(),Toast.LENGTH_LONG).show(); }
    }
}
