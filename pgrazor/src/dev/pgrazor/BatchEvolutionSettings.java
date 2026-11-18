package dev.pgrazor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Session-only controls for a manually previewed batch of tagged Pokemon. */
public final class BatchEvolutionSettings {
    private BatchEvolutionSettings() {}

    private static final Object LOCK=new Object();
    private static long nextToken=System.currentTimeMillis()>0?System.currentTimeMillis():1;
    private static long token;
    private static int mode;
    private static String tag="Évoluer";
    private static boolean removeTag;
    private static String phase="idle";
    private static String message="Entre une étiquette, puis prévisualise le lot.";
    private static long total,completed,skipped,xp,elapsedMs;
    private static long candyCost=-1,itemCost=-1;

    /** Polled by the transport daemon; mode 0 is idle/stop, 1 preview, 2 run. */
    public static String config() {
        synchronized(LOCK) {return "2|"+token+"|"+mode+"|"+(removeTag?1:0)+"|"+tag;}
    }

    public static boolean enabled() {
        synchronized(LOCK) {return mode!=0||"stopping".equals(phase);}
    }

    /** A stale or malformed event must never change the current preview. */
    public static void publish(String json) {
        if(json==null)return;
        try {
            JSONObject event=new JSONObject(json);
            if(!"batch-evolution".equals(strictString(event,"kind")))return;
            long eventToken=whole(event,"token");
            String eventPhase=strictString(event,"phase");
            if(!validPhase(eventPhase))return;
            String eventMessage=strictString(event,"message");
            long eventTotal=nonnegative(event,"total");
            long eventCompleted=nonnegative(event,"completed");
            long eventSkipped=nonnegative(event,"skipped");
            long eventXp=nonnegative(event,"xp");
            long eventElapsed=nonnegative(event,"elapsedMs");
            if(eventCompleted>eventTotal)return;
            long eventCandy=optionalNonnegative(event,"candyCost");
            long eventItems=optionalNonnegative(event,"itemCount");
            synchronized(LOCK) {
                if(token==0||eventToken!=token||(mode==0&&!"stopping".equals(phase)))return;
                boolean stopping="stopping".equals(phase);
                if(stopping&&!("running".equals(eventPhase)||"done".equals(eventPhase)
                    ||"stopped".equals(eventPhase)||"error".equals(eventPhase)))return;
                if(mode==1&&"running".equals(eventPhase))return;
                if(mode==2&&("scanning".equals(eventPhase)||"ready".equals(eventPhase)))return;
                if(!stopping||"done".equals(eventPhase)||"stopped".equals(eventPhase)
                    ||"error".equals(eventPhase)) {
                    phase=eventPhase;message=NativeText.fr(eventMessage);
                }
                total=eventTotal;completed=eventCompleted;skipped=eventSkipped;
                xp=eventXp;elapsedMs=eventElapsed;
                candyCost=eventCandy;itemCost=eventItems;
                if("done".equals(phase)||"stopped".equals(phase)||"error".equals(phase))mode=0;
            }
        } catch(Exception ignored) {}
    }

    /** Called on logout/account reset. This also invalidates late native events. */
    public static void reset() {
        synchronized(LOCK) {invalidate();tag="Évoluer";removeTag=false;}
    }

    private static long whole(JSONObject event,String key) throws Exception {
        Object value=event.get(key);
        if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException(key);
        Number number=(Number)value;
        long result=number.longValue();
        if(number.doubleValue()!=result)throw new IllegalArgumentException(key);
        return result;
    }
    private static String strictString(JSONObject event,String key) throws Exception {
        Object value=event.get(key);
        if(!(value instanceof String))throw new IllegalArgumentException(key);
        return (String)value;
    }
    private static long nonnegative(JSONObject event,String key) throws Exception {
        long value=whole(event,key);
        if(value<0)throw new IllegalArgumentException(key);
        return value;
    }
    private static long optionalNonnegative(JSONObject event,String key) throws Exception {
        return event.has(key)?nonnegative(event,key):-1;
    }
    private static boolean validPhase(String value) {
        return "scanning".equals(value)||"ready".equals(value)||"running".equals(value)
            ||"done".equals(value)||"stopped".equals(value)||"error".equals(value);
    }
    private static void invalidate() {
        token=0;mode=0;phase="idle";message="Entre une étiquette, puis prévisualise le lot.";
        total=completed=skipped=xp=elapsedMs=0;candyCost=itemCost=-1;
    }
    private static long freshToken() {
        if(nextToken==Long.MAX_VALUE)nextToken=1;
        else nextToken++;
        return nextToken;
    }
    private static String normalizedTag(String input) {
        String value=input.trim();
        if(value.startsWith("#"))value=value.substring(1).trim();
        int bytes=value.getBytes(StandardCharsets.UTF_8).length;
        if(bytes<1||bytes>64)throw new IllegalArgumentException("Entre une étiquette de 1 à 64 octets UTF-8.");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='|'||Character.isISOControl(c))throw new IllegalArgumentException("L'étiquette ne peut pas contenir de barre verticale ni de caractère de contrôle.");
        }
        return value;
    }

    public static void show(Activity activity) {
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        Context ui=MapTheme.wrap(activity);
        LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);
        int pad=MapTheme.dp(ui,16);body.setPadding(pad,pad,pad,pad);
        TextView explanation=new TextView(ui);
        explanation.setText("Choisis une étiquette pour prévisualiser les Pokémon éligibles. Chacun évolue une fois, sans animation. Les favoris, ton copain, les Pokémon en arène, les œufs, les Pokémon Obscurs et purifiés, et les évolutions à choix multiple ou à condition spéciale sont ignorés. Bonbons et objets sont vérifiés avant chaque évolution. Lance ton Œuf Chance toi-même si tu veux.");
        body.addView(explanation);
        EditText tagInput=new EditText(ui);tagInput.setSingleLine(true);tagInput.setHint("Nom de l'étiquette (par exemple, Évoluer)");
        synchronized(LOCK) {tagInput.setText(tag);}
        body.addView(tagInput);
        CheckBox remove=new CheckBox(ui);
        remove.setText("Retirer cette étiquette après chaque évolution confirmée");
        synchronized(LOCK) {remove.setChecked(removeTag);}
        body.addView(remove);
        TextView status=new TextView(ui);body.addView(status);
        Button preview=new Button(ui);preview.setAllCaps(false);preview.setText("Prévisualiser le lot");body.addView(preview);
        Button start=new Button(ui);start.setAllCaps(false);start.setText("Lancer le lot");body.addView(start);
        Button stop=new Button(ui);stop.setAllCaps(false);stop.setText("Stop (l'évolution en cours peut se terminer)");body.addView(stop);
        ScrollView scroll=new ScrollView(ui);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Évolution en lot").setView(scroll)
            .setPositiveButton("Fermer",null).create();
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable refresh=new Runnable(){public void run(){
            if(!dialog.isShowing())return;
            synchronized(LOCK) {
                boolean busy="scanning".equals(phase)||"running".equals(phase)||"stopping".equals(phase);
                tagInput.setEnabled(!busy);remove.setEnabled(!busy);preview.setEnabled(!busy);
                start.setEnabled(mode==1&&"ready".equals(phase)&&total>0);
                stop.setEnabled(mode!=0);
                status.setText(summary());
            }
            handler.postDelayed(this,1000);
        }};
        tagInput.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int offset,int count,int after){}
            public void onTextChanged(CharSequence s,int offset,int before,int count){
                synchronized(LOCK) {if(!"scanning".equals(phase)&&!"running".equals(phase)
                    &&!"stopping".equals(phase))invalidate();}
                refreshControls(status,tagInput,remove,preview,start,stop);
            }
            public void afterTextChanged(Editable s){}
        });
        remove.setOnCheckedChangeListener((button,checked)->{
            synchronized(LOCK) {
                if("scanning".equals(phase)||"running".equals(phase)||"stopping".equals(phase))return;
                invalidate();removeTag=checked;
            }
            refreshControls(status,tagInput,remove,preview,start,stop);
        });
        preview.setOnClickListener(v->{
            if(AutoRaid.running()||OneShotAutomation.enabled()||GiftBatchSettings.running()){android.widget.Toast.makeText(activity,"Arrête d'abord la tâche d'automatisation en cours.",android.widget.Toast.LENGTH_SHORT).show();return;}
            String value;
            try {value=normalizedTag(tagInput.getText().toString());}
            catch(IllegalArgumentException e){tagInput.setError(e.getMessage());return;}
            synchronized(LOCK) {
                invalidate();tag=value;token=freshToken();mode=1;phase="scanning";
                message="Analyse #"+tag+"...";
            }
            refreshControls(status,tagInput,remove,preview,start,stop);
        });
        start.setOnClickListener(v->{
            final long expectedToken;
            final long count;
            final long candy,items;
            final boolean removeAfter;
            synchronized(LOCK) {
                if(mode!=1||!"ready".equals(phase)||total<=0)return;
                expectedToken=token;count=total;candy=candyCost;items=itemCost;removeAfter=removeTag;
            }
            String cost=(candy>=0&&items>=0)
                ?"Jusqu'à "+candy+" Bonbons et "+items+" objets d'évolution peuvent être consommés. Chaque coût est vérifié avant l'évolution."
                :"Les totaux de Bonbons et d'objets d'évolution sont indisponibles ; chaque coût est vérifié avant l'évolution.";
            AlertDialog confirm=new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Lancer l'évolution en lot ?")
                .setMessage(count+" Pokémon éligibles vont évoluer une fois. "+cost
                    +(removeAfter?" The selected tag will be removed after each confirmed evolution.":"")
                    +" Aucun Œuf Chance ne sera utilisé automatiquement.")
                .setNegativeButton("Annuler",null).setPositiveButton("Lancer le lot",(d,which)->{
                    synchronized(LOCK) {
                        if(token==expectedToken&&mode==1&&"ready".equals(phase)&&total>0) {
                            mode=2;phase="running";message="Lancement du lot…";
                        }
                    }
                    refreshControls(status,tagInput,remove,preview,start,stop);
                }).create();
            DialogLifecycle.bind(activity,confirm,null);
            confirm.setOnShowListener(d->MapTheme.styleButtons(confirm));confirm.show();
        });
        stop.setOnClickListener(v->{
            synchronized(LOCK) {
                if(mode==0||token==0)return;
                mode=0;phase="stopping";
                message="Arrêt. L'évolution en cours peut se terminer avant l'arrêt du lot.";
            }
            refreshControls(status,tagInput,remove,preview,start,stop);
        });
        DialogLifecycle.bind(activity,dialog,()->handler.removeCallbacks(refresh));
        dialog.setOnShowListener(d->{MapTheme.style(body);MapTheme.styleButtons(dialog);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(MapTheme.surface()));
            handler.post(refresh);
        });
        dialog.show();
    }

    private static void refreshControls(TextView status,EditText tagInput,CheckBox remove,
                                        Button preview,Button start,Button stop) {
        synchronized(LOCK) {
            boolean busy="scanning".equals(phase)||"running".equals(phase)||"stopping".equals(phase);
            tagInput.setEnabled(!busy);remove.setEnabled(!busy);preview.setEnabled(!busy);
            start.setEnabled(mode==1&&"ready".equals(phase)&&total>0);
            stop.setEnabled(mode!=0);status.setText(summary());
        }
    }
    private static String summary() {
        String shownPhase=phase.substring(0,1).toUpperCase(Locale.US)+phase.substring(1);
        long seconds=elapsedMs/1000;
        return shownPhase+": "+message+"\nÉligibles : "+total+"   Terminés : "+completed
            +"   Ignorés : "+skipped+"\nXP : "+xp+"   Temps : "+String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
    }
}
