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
public final class BuddyEmotionSettings {
    private BuddyEmotionSettings() {}
    private static final BuddyEmotionPreferences preferences=new BuddyEmotionPreferences();
    private static final int[] BERRIES={701,703,705,706,708};
    private static final String[] BERRY_LABELS={"Baie Framby","Baie Nanab","Baie Nanana","Baie Framby dorée (premium)","Baie Nanana argentée (premium)"};
    private static volatile String status="Off", savedHistory="", metric="EP : en attente";
    public static String status(){return metric+"\n"+status;}
    public static String history(){return savedHistory;}
    public static void publish(String json){try{
        org.json.JSONObject event=new org.json.JSONObject(json);status=NativeText.fr(event.optString("message","En attente"));
        int ep=event.optInt("ep",-1);metric=ep<0?"EP : en attente":"EP : "+ep+(event.optBoolean("excited",false)?" | Excité":"");
        long seconds=event.optLong("maintenanceSeconds",0);
        if(seconds>0)metric+=" | Maintenance ~"+(seconds/60)+"m "+(seconds%60)+"s";
        String account=event.optString("account",""), h=event.optString("history","");
        if(AccountProfiles.matchesAutomationIdentity(account)&&h.matches("[0-9]+(;[0-9]+){5}")&&!h.startsWith("0;")&&h.length()<=256){
            String next=account+"|"+h;if(!next.equals(savedHistory)){savedHistory=next;Control.saveSettings();}
        }
    }catch(Exception ignored){}}
    public static void read(Properties p){preferences.read(p);String h=p.getProperty("buddyEmotionHistory","");savedHistory=h.length()<=256?h:"";status="En attente des données du copain";metric="EP : en attente";}
    public static void write(Properties p){preferences.write(p);p.setProperty("buddyEmotionHistory",savedHistory);}
    public static String config(){return preferences.config();}
    public static boolean enabled(){return preferences.enabled();}
    public static boolean overlayEnabled(){return preferences.enabled()&&preferences.overlay();}
    private static void set(String key,Object value){preferences.set(key,String.valueOf(value));persist();}
    private static void persist(){Control.saveSettings();}
    /** Builds the same account-aware form used inline and in the modal. */
    public static Runnable populate(Activity activity,LinearLayout body){
        Context ui=body.getContext();
        TextView description=new TextView(ui);
        description.setText("Photos et caresses auto pour monter l'humeur du copain, jeu au premier plan. Marche et combats restent manuels.");body.addView(description);
        CheckBox master=new CheckBox(ui);master.setText("Humeur du copain auto");body.addView(master);
        TextView progress=new TextView(ui);body.addView(progress);
        TextView priority=button(body,"");
        TextView reserve=button(body,"");
        CheckBox gifts=new CheckBox(ui);gifts.setText("Ouvrir ses cadeaux");body.addView(gifts);
        CheckBox overlay=new CheckBox(ui);overlay.setText("EP dans le panneau sur la carte");body.addView(overlay);
        TextView explanation=new TextView(ui);explanation.setText("Donne la 1re baie de la liste dont le stock dépasse la réserve. Liste vide : pas de nourrissage.");body.addView(explanation);
        final boolean[] refreshing={false};
        Runnable refresh=()->{
            refreshing[0]=true;
            boolean on=preferences.enabled();master.setChecked(on);gifts.setChecked(preferences.gifts());
            overlay.setChecked(preferences.overlay());
            progress.setText(status());priority.setText("Baies : "+priorityLabel(preferences.berries()));
            reserve.setText("Réserve par baie : "+preferences.reserve());
            for(android.view.View v:new android.view.View[]{priority,reserve,gifts,overlay,explanation}){v.setEnabled(on);v.setAlpha(on?1f:0.45f);}
            refreshing[0]=false;
        };
        master.setOnCheckedChangeListener((v,on)->{if(!refreshing[0]){set("Enabled",on);refresh.run();}});
        gifts.setOnCheckedChangeListener((v,on)->{if(!refreshing[0])set("Gifts",on);});
        overlay.setOnCheckedChangeListener((v,on)->{if(!refreshing[0])set("Overlay",on);});
        priority.setOnClickListener(v->priorityPicker(activity,refresh));
        reserve.setOnClickListener(v->reservePicker(activity,refresh));
        refresh.run();return refresh;
    }
    public static void show(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        Context ui=MapTheme.wrap(activity);LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);
        int pad=MapTheme.dp(ui,16);body.setPadding(pad,pad,pad,pad);
        Runnable update=populate(activity,body);ScrollView scroll=new ScrollView(ui);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Moteur d'émotions du copain").setView(scroll).setPositiveButton("OK",null).create();
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh=new Runnable(){public void run(){if(dialog.isShowing()){update.run();handler.postDelayed(this,1000);}}};
        DialogLifecycle.bind(activity,dialog,()->handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d->{MapTheme.style(body);MapTheme.styleButtons(dialog);dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(MapTheme.surface()));handler.post(refresh);});dialog.show();
    }
    private static TextView button(LinearLayout body,String text){
        android.widget.Button b=new android.widget.Button(body.getContext());b.setText(text);b.setAllCaps(false);body.addView(b);return b;
    }
    private static String priorityLabel(String value){
        if(value.isEmpty())return "Pas de nourrissage";
        StringBuilder result=new StringBuilder();
        for(String id:value.split(","))for(int i=0;i<BERRIES.length;i++)if(id.equals(String.valueOf(BERRIES[i]))){
            if(result.length()>0)result.append(" > ");result.append(BERRY_LABELS[i]);
        }
        return result.toString();
    }
    private static boolean sameAccount(String initial){return java.util.Objects.equals(initial,AccountProfiles.currentId());}
    private static void priorityPicker(Activity activity,Runnable refresh){
        Context ui=MapTheme.wrap(activity);String account=AccountProfiles.currentId();
        java.util.ArrayList<String> order=new java.util.ArrayList<>();
        if(!preferences.berries().isEmpty())java.util.Collections.addAll(order,preferences.berries().split(","));
        LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);
        TextView help=new TextView(ui);help.setText("Touche les baies dans l'ordre où les essayer. Décoche puis recoche une baie pour la mettre à la fin. Les baies premium doivent être choisies explicitement.");body.addView(help);
        CheckBox[] rows=new CheckBox[BERRIES.length];boolean[] syncing={false};
        Runnable redraw=()->{syncing[0]=true;for(int i=0;i<rows.length;i++){
            int position=order.indexOf(String.valueOf(BERRIES[i]));rows[i].setChecked(position>=0);
            rows[i].setText((position<0?"":(position+1)+". ")+BERRY_LABELS[i]);
        }syncing[0]=false;};
        for(int i=0;i<rows.length;i++){final String id=String.valueOf(BERRIES[i]);rows[i]=new CheckBox(ui);body.addView(rows[i]);rows[i].setOnCheckedChangeListener((v,on)->{
            if(syncing[0])return;if(on){if(!order.contains(id))order.add(id);}else order.remove(id);redraw.run();
        });}
        LinearLayout actions=new LinearLayout(ui);actions.setOrientation(LinearLayout.HORIZONTAL);body.addView(actions);
        TextView clear=button(actions,"Effacer"),reset=button(actions,"Réinitialiser");
        for(TextView action:new TextView[]{clear,reset}){
            action.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
            action.setMinHeight(MapTheme.dp(ui,48));
        }
        clear.setOnClickListener(v->{order.clear();redraw.run();});
        reset.setOnClickListener(v->{order.clear();java.util.Collections.addAll(order,"703","701","705");redraw.run();});
        redraw.run();ScrollView scroll=new ScrollView(ui);scroll.addView(body);
        AlertDialog d=new AlertDialog.Builder(ui).setTitle("Priorité de nourrissage").setView(scroll).setPositiveButton("Enregistrer",null).setNegativeButton("Annuler",null).create();
        DialogLifecycle.bind(activity,d,null);d.setOnShowListener(x->{MapTheme.style(body);MapTheme.styleButtons(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(sameAccount(account)&&preferences.enabled())set("Berries",android.text.TextUtils.join(",",order));refresh.run();d.dismiss();
        });});d.show();
    }
    private static void reservePicker(Activity activity,Runnable refresh){
        Context ui=MapTheme.wrap(activity);String account=AccountProfiles.currentId();EditText edit=new EditText(ui);
        edit.setSingleLine(true);edit.setText(String.valueOf(preferences.reserve()));edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog d=new AlertDialog.Builder(ui).setTitle("Réserve par baie (0-1000)").setView(edit).setPositiveButton("Enregistrer",null).setNegativeButton("Annuler",null).create();
        DialogLifecycle.bind(activity,d,null);d.setOnShowListener(x->{MapTheme.style(edit);MapTheme.styleButtons(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!sameAccount(account)||!preferences.enabled()){refresh.run();d.dismiss();return;}
            try{int n=Integer.parseInt(edit.getText().toString().trim());if(n<0||n>1000)throw new NumberFormatException();set("Reserve",n);refresh.run();d.dismiss();}
            catch(NumberFormatException invalid){edit.setError("Entre un nombre entier de 0 à 1000.");}
        });});d.show();
    }
}
