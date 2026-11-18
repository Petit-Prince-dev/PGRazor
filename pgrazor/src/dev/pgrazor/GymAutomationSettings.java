package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Properties;

/** Account settings are persisted by Control alongside its other properties. */
public final class GymAutomationSettings {
    private GymAutomationSettings() {}
    private static final GymAutomationPreferences preferences=new GymAutomationPreferences();
    private static final Object lock=new Object();
    private static volatile String status="Automatisation d'arène désactivée",guardReason="",backendStatus="";
    public static void publish(String detail){try{backendStatus=new org.json.JSONObject(detail).optString("message","Waiting");status=backendStatus;}catch(Exception ignored){}}
    public static String status(){return guardReason.isEmpty()?status:guardReason;}
    /** Short, stable label for Pad's status pill beneath the icon. */
    public static String overlayStatus(){
        stopForCooldown();
        if(!guardReason.isEmpty())return guardReason.contains("cooldown")?"Cooldown":"Choisis une action";
        if(!preferences.running())return "Off";
        String raw=status.toLowerCase(java.util.Locale.US);
        if(raw.contains("paused")||raw.contains("failure"))return "En pause";
        if(raw.contains("bag full")||raw.contains("item bag full"))return "Sac plein";
        if(raw.contains("deploying"))return "Placement du défenseur";
        if(raw.contains("deployed"))return "Défenseur placé";
        if(raw.contains("feeding"))return "Don de baies";
        if(raw.contains("berry accepted"))return "Baie acceptée";
        if(raw.contains("berry declined"))return "Baie refusée";
        if(raw.contains("selecting"))return "Choix du défenseur";
        if(raw.contains("reserve"))return "Réserves de baies atteintes";
        if(raw.contains("map readiness"))return "En attente de la carte";
        if(raw.contains("inventory update"))return "En attente de l'inventaire";
        if(raw.contains("waiting")||raw.contains("nearby")||raw.contains("nearest")
            ||raw.contains("eligible"))return "En attente de l'arène";
        if(raw.contains("rejected")||raw.contains("declined"))return "Action refusée";
        return "Démarrage";
    }
    public static void init(Context context) {}
    public static void read(Properties p){synchronized(lock){preferences.read(p);guardReason="";backendStatus="";status="Automatisation d'arène désactivée";}}
    public static void write(Properties p){preferences.write(p);}
    public static String config(){stopForCooldown();return preferences.config(SmartCatchSettings.gymSpinActive());}
    public static boolean enabled(){stopForCooldown();return (preferences.running()&&(preferences.deploy()||preferences.feed()))||SmartCatchSettings.gymSpinActive();}
    public static boolean running(){stopForCooldown();return preferences.running();}
    public static boolean feedEnabled(){stopForCooldown();return preferences.effectiveFeed();}
    public static boolean deployEnabled(){stopForCooldown();return preferences.effectiveDeploy();}
    public static boolean overlayEnabled(){return preferences.overlay();}
    /** Stop the whole run on cooldown while retaining its selected actions. */
    public static boolean stopForCooldown(){
        if(!preferences.running()||Cooldown.remaining()<=0)return false;
        boolean changed=false;
        synchronized(lock){
            if(preferences.stopForCooldown(true)){
                guardReason="Automatisation d'arène arrêtée : cooldown actif. Touche pour relancer après la fin.";
                changed=true;
            }
        }
        return changed;
    }
    /** Tap control for both selected actions; no automatic resume after cooldown. */
    public static boolean toggleRunning(){return setRunning(!running());}
    private static boolean setRunning(boolean on){
        if(on&&AutoRaid.running()){guardReason="Arrête l'Auto-Raid avant de lancer l'automatisation d'arène.";return false;}
        stopForCooldown();
        boolean cooldownActive=Cooldown.remaining()>0;
        synchronized(lock){
            if(!on){preferences.stop();status="Automatisation d'arène arrêtée";return false;}
            if(cooldownActive){
                guardReason="Automatisation d'arène désactivée : cooldown actif. Réessaie après la fin.";
                return false;
            }
            if(!preferences.start()){
                guardReason="Choisis « Placer » ou « Nourrir » dans les réglages d'arène avant de démarrer.";
                return false;
            }
            guardReason="";status=backendStatus.startsWith("Paused:")?backendStatus:"En attente de l'arène";return true;
        }
    }
    private static void set(String key,Object value){
        synchronized(lock){
            boolean wasRunning=preferences.running();preferences.set(key,String.valueOf(value));
            if(wasRunning&&!preferences.running())status="Automatisation d'arène arrêtée : choisis « Placer » ou « Nourrir ».";
            if(("Deploy".equals(key)||"Feed".equals(key))
                &&(preferences.deploy()||preferences.feed())
                &&guardReason.startsWith("Select Deploy"))guardReason="";
        }
        persist();
    }
    private static void persist(){Control.saveSettings();}
    public static void show(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        Context ui=MapTheme.wrap(activity);
        LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);
        int pad=MapTheme.dp(ui,16);body.setPadding(pad,pad,pad,pad);
        TextView description=new TextView(ui);
        TextView progress=new TextView(ui);progress.setText(status());body.addView(progress);
        description.setText("Agit seulement à moins de 80 m d'une Arène quand le jeu est sur la carte. Place d'abord les Pokémon éligibles étiquetés #Gym ; le secours « PC le plus bas » n'est utilisé que s'il est activé et qu'aucun défenseur #Gym n'est disponible, et ne prend jamais de favori, de copain ni de Pokémon protégé. Le nourrissage utilise les Nanab avant les Framby et seulement au-dessus des réserves choisies. Respecte les limites du serveur du jeu, certaines actions peuvent être refusées. Aucune Poussière ni Pièce n'est garantie.");body.addView(description);
        CheckBox master=new CheckBox(ui);master.setText("Lancer le placement en arène & nourrissage");body.addView(master);
        CheckBox deploy=new CheckBox(ui);deploy.setText("Placer automatiquement dans les places libres en Arène");deploy.setChecked(preferences.deploy());body.addView(deploy);

        CheckBox feed=new CheckBox(ui);feed.setText("Nourrir automatiquement tes défenseurs d'Arène");feed.setChecked(preferences.feed());body.addView(feed);
        CheckBox overlay=new CheckBox(ui);overlay.setText("Afficher l'overlay d'automatisation d'arène");overlay.setChecked(preferences.overlay());body.addView(overlay);

        CheckBox fallback=new CheckBox(ui);fallback.setText("Autoriser le secours « PC le plus bas » sans défenseur #Gym éligible");fallback.setChecked(preferences.fallback());body.addView(fallback);
        fallback.setOnCheckedChangeListener((v,on)->{set("Fallback",on);});
        LinearLayout reserves=new LinearLayout(ui);reserves.setOrientation(LinearLayout.VERTICAL);body.addView(reserves);
        input(activity,reserves,"Réserve de Baies Nanab (0–1000)",String.valueOf(preferences.nanabReserve()),value->{int n=bounded(value,1000);set("NanabReserve",n);});
        input(activity,reserves,"Réserve de Baies Framby (0–1000)",String.valueOf(preferences.razzReserve()),value->{int n=bounded(value,1000);set("RazzReserve",n);});
        final boolean[] refreshing={false};
        Runnable update=()->{
            refreshing[0]=true;master.setChecked(running());deploy.setChecked(preferences.deploy());
            feed.setChecked(preferences.feed());overlay.setChecked(preferences.overlay());
            SettingsUi.enabled(fallback,preferences.deploy());SettingsUi.enabled(reserves,preferences.feed());
            progress.setText(status());refreshing[0]=false;
        };
        master.setOnCheckedChangeListener((v,on)->{if(!refreshing[0]){setRunning(on);update.run();}});
        deploy.setOnCheckedChangeListener((v,on)->{if(!refreshing[0]){set("Deploy",on);update.run();}});
        feed.setOnCheckedChangeListener((v,on)->{if(!refreshing[0]){set("Feed",on);update.run();}});
        overlay.setOnCheckedChangeListener((v,on)->{if(!refreshing[0]){set("Overlay",on);update.run();}});
        body.removeView(feed);body.addView(feed,body.indexOfChild(fallback)+1);
        body.removeView(overlay);body.addView(overlay,body.indexOfChild(feed)+1);
        update.run();
        ScrollView scroll=new ScrollView(ui);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Placement en arène & nourrissage").setView(scroll).setPositiveButton("OK",null).create();
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh=new Runnable(){public void run(){if(dialog.isShowing()){update.run();handler.postDelayed(this,1000);}}};
        DialogLifecycle.bind(activity,dialog,()->handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d->{MapTheme.style(body);MapTheme.styleButtons(dialog);dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(MapTheme.surface()));handler.post(refresh);});dialog.show();
    }
    private interface Value {void set(String value);}
    private static int bounded(String value,int max){
        try{int n=Integer.parseInt(value.trim());if(n>=0&&n<=max)return n;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Entre un nombre entier de 0 à "+max+".");
    }
    private static TextView button(LinearLayout body,String text){
        android.widget.Button b=new android.widget.Button(body.getContext());b.setText(text);b.setAllCaps(false);body.addView(b);return b;
    }
    private static void input(Activity a,LinearLayout body,String title,String initial,Value save){
        TextView button=button(body,title+": "+initial);final String[] current={initial};
        button.setOnClickListener(v->{
            Context ui=MapTheme.wrap(a);EditText edit=new EditText(ui);edit.setSingleLine(true);edit.setText(current[0]);
            edit.setInputType(InputType.TYPE_CLASS_NUMBER);
            AlertDialog d=new AlertDialog.Builder(ui).setTitle(title).setView(edit).setPositiveButton("Enregistrer",null).setNegativeButton("Annuler",null).create();
            DialogLifecycle.bind(a,d,null);d.setOnShowListener(x->{MapTheme.style(edit);MapTheme.styleButtons(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                try{String value=edit.getText().toString();save.set(value);current[0]=value.trim();button.setText(title+": "+current[0]);d.dismiss();}
                catch(IllegalArgumentException e){edit.setError(e.getMessage());}
            });});d.show();
        });
    }
}
