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
public final class SmartCatchSettings {
    private SmartCatchSettings() {}
    private static final SmartCatchPreferences preferences=new SmartCatchPreferences();
    private static final int[] BERRIES={0,701,703,705,706,708};
    private static final String[] SPEED_NAMES={"Normal (environ 1,5 s)","Rapide (environ 1 s)","Très rapide (environ 0,5 s)"};
    private static final String[] BERRY_NAMES={"Aucune","Framby","Nanab","Nanana","Framby dorée","Nanana argentée"};
    public static void init(Context context) {}
    public static void read(Properties p){preferences.read(p);}
    public static void write(Properties p){preferences.write(p);}
    public static String config(){return preferences.config();}
    /** Gym-disc spinning follows VGP's actual run/cooldown gate, not catch availability. */
    public static boolean gymSpinActive(){return preferences.enabled()&&preferences.spinGyms()&&Control.vgpEnabled();}
    public static String normalizeSpecies(String value){return SmartCatchPreferences.normalizeSpecies(value);}
    static int speed(){return preferences.speed();}
    static boolean shinyOnly(){return preferences.shinyOnly();}
    static String candySpecies(){return preferences.candySpecies();}
    static void setCandySpecies(String value){set("CandySpecies",value);}
    static String catchSpecies(){return preferences.catchSpecies();}
    static void setCatchSpecies(String value){set("CatchSpecies",value);}
    private static String count(String ids,String none){return ids.isEmpty()?none:ids.split(",").length+" sélectionnés";}
    private static void set(String key,Object value){preferences.set(key,String.valueOf(value));persist();}
    private static void persist(){Control.saveSettings();}
    public static Runnable populate(Activity activity,LinearLayout body){
        Context ui=body.getContext();
        CheckBox mode=new CheckBox(ui);mode.setText("VGP+");mode.setChecked(preferences.enabled());body.addView(mode);
        LinearLayout options=new LinearLayout(ui);options.setOrientation(LinearLayout.VERTICAL);body.addView(options);
        TextView description=new TextView(ui);description.setText("Utilise l'overlay VGP pour démarrer ou arrêter. Le VGP+ ajoute le choix de Ball, de baie et de lancer. Les cibles à PC/niveau élevé utilisent des Hyper Balls ; mets un seuil à 0 pour le désactiver.");options.addView(description);
        TextView standard=row(activity,options,"Ball standard",new String[]{"Poké Ball","Super Ball","Hyper Ball"},preferences.ball()-1,index->{set("Ball",index+1);});
        CheckBox auto=new CheckBox(ui);auto.setText("Ball auto VGP+");auto.setChecked(preferences.autoBall());options.addView(auto);
        TextView autoTarget=button(options,"Cible Ball auto VGP+ : "+preferences.autoBallTarget()+"%");
        TextView autoHelp=new TextView(ui);autoHelp.setText("Remplace la Ball standard : lance la Ball la moins chère dont la chance de capture atteint la cible, d'après le taux du serveur avec ta baie, ton lancer et ta balle courbe. Les règles Hyper Ball ci-dessous s'appliquent toujours, et une Master Ball n'est jamais utilisée.");options.addView(autoHelp);
        autoTarget.setOnClickListener(v->{
            int[] targets=Control.AUTO_BALL_TARGETS;int next=targets[0];
            for(int i=0;i<targets.length;i++)if(targets[i]==preferences.autoBallTarget()){next=targets[(i+1)%targets.length];break;}
            set("AutoBallTarget",next);autoTarget.setText("Cible Ball auto VGP+ : "+next+"%");});
        int berryIndex=0;for(int i=0;i<BERRIES.length;i++)if(BERRIES[i]==preferences.berry())berryIndex=i;
        row(activity,options,"Baie standard",BERRY_NAMES,berryIndex,index->{set("Berry",BERRIES[index]);});
        CheckBox special=new CheckBox(ui);special.setText("Shiny ou IV ≥90 % : Hyper Ball + Framby dorée");special.setChecked(preferences.premium());options.addView(special);
        special.setOnCheckedChangeListener((v,on)->{set("Premium",on);});
        input(activity,options,"Seuil de PC élevé (0–10000)",String.valueOf(preferences.highCp()),value->{int n=bounded(value,10000);set("HighCp",n);},true);
        input(activity,options,"Seuil de niveau élevé (0–50)",String.valueOf(preferences.highLevel()),value->{int n=bounded(value,50);set("HighLevel",n);},true);
        TextView rating=row(activity,options,"Qualité du lancer",new String[]{"Default","Bien","Super","Excellent"},preferences.grade(),index->{set("Grade",index);});
        Runnable[] afterVariety={null};
        row(activity,options,"Lancers variés",Control.THROW_VARIETY_LABELS,preferences.variety(),index->{set("Variety",index);if(afterVariety[0]!=null)afterVariety[0].run();});
        TextView varietyHelp=new TextView(ui);varietyHelp.setText("Lancers variés choisit une qualité au hasard à chaque lancer, à chances égales. « Tous » inclut un lancer simple. Remplace la qualité du lancer quand c'est activé.");options.addView(varietyHelp);
        CheckBox curved=new CheckBox(ui);curved.setText("Balle courbe");curved.setChecked(preferences.curve());options.addView(curved);
        curved.setOnCheckedChangeListener((v,on)->{set("Curve",on);});
        row(activity,options,"Vitesse de capture",SPEED_NAMES,preferences.speed(),index->{set("Speed",index);});
        TextView speedHelp=new TextView(ui);speedHelp.setText("Temps d'attente du VGP+ avant chaque lancer et avant de chercher le Pokémon suivant. Plus c'est rapide, moins ça ressemble à un jeu normal.");options.addView(speedHelp);
        CheckBox gymSpin=new CheckBox(ui);gymSpin.setText("Spin auto des disques d'Arène");gymSpin.setChecked(preferences.spinGyms());options.addView(gymSpin);
        TextView gymSpinHelp=new TextView(ui);gymSpinHelp.setText("Pendant que le VGP tourne sur la carte, fait tourner les disques d'Arène de toute équipe à moins de 80 m si le sac a de la place.");options.addView(gymSpinHelp);
        gymSpin.setOnCheckedChangeListener((v,on)->{if(on!=preferences.spinGyms())set("SpinGyms",on);});
        TextView candy=button(options,"Espèces à Bonbons (Nanana) : "+count(preferences.candySpecies(),"Aucune"));
        candy.setOnClickListener(v->SmartCatchSpeciesDialog.show(activity,()->candy.setText("Espèces à Bonbons (Nanana) : "+count(preferences.candySpecies(),"Aucune"))));
        CheckBox shinyOnly=new CheckBox(ui);shinyOnly.setText("Capturer seulement les shiny");shinyOnly.setChecked(preferences.shinyOnly());options.addView(shinyOnly);
        TextView shinyOnlyHelp=new TextView(ui);shinyOnlyHelp.setText("Le VGP+ ouvre quand même chaque Pokémon pour voir s'il est shiny, mais ne lance que sur les shiny et laisse le reste sur la carte. Chaque ouverture compte comme une rencontre.");options.addView(shinyOnlyHelp);
        shinyOnly.setOnCheckedChangeListener((v,on)->{if(on!=preferences.shinyOnly())set("ShinyOnly",on);});
        TextView only=button(options,"Capturer seulement : "+count(preferences.catchSpecies(),"Toutes les espèces"));
        TextView onlyHelp=new TextView(ui);onlyHelp.setText("Quand des espèces sont choisies, le VGP+ capture seulement celles-ci et laisse tous les autres Pokémon sur la carte, shiny ou non. Avec « Capturer seulement les shiny » activé aussi, il ne capture que les shiny de ces espèces.");options.addView(onlyHelp);
        only.setOnClickListener(v->SmartCatchSpeciesDialog.showCatchOnly(activity,()->only.setText("Capturer seulement : "+count(preferences.catchSpecies(),"Toutes les espèces"))));
        Runnable refresh=()->{mode.setChecked(preferences.enabled());gymSpin.setChecked(preferences.spinGyms());auto.setChecked(preferences.autoBall());SettingsUi.enabled(mode,Control.vgpOverlay());SettingsUi.enabled(options,Control.vgpOverlay()&&preferences.enabled());if(Control.vgpOverlay()&&preferences.enabled()){SettingsUi.enabled(autoTarget,preferences.autoBall());SettingsUi.enabled(standard,!preferences.autoBall());SettingsUi.enabled(rating,preferences.variety()==0);}};
        afterVariety[0]=refresh;
        auto.setOnCheckedChangeListener((v,on)->{if(on!=preferences.autoBall())set("AutoBall",on);refresh.run();});
        mode.setOnCheckedChangeListener((v,on)->{if(on!=preferences.enabled())set("Enabled",on);refresh.run();});
        refresh.run();return refresh;
    }
    public static void show(Activity activity){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return;
        Context ui=MapTheme.wrap(activity);LinearLayout body=new LinearLayout(ui);body.setOrientation(LinearLayout.VERTICAL);
        int pad=MapTheme.dp(ui,16);body.setPadding(pad,pad,pad,pad);Runnable refresh=populate(activity,body);
        ScrollView scroll=new ScrollView(ui);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("VGP+").setView(scroll).setPositiveButton("OK",null).create();
        DialogLifecycle.bind(activity,dialog,null);dialog.setOnShowListener(d->{MapTheme.style(body);MapTheme.styleButtons(dialog);refresh.run();});dialog.show();
    }
    private interface Choice {void set(int index);}
    private interface Value {void set(String value);}
    private static int bounded(String value,int max){
        try{int n=Integer.parseInt(value.trim());if(n>=0&&n<=max)return n;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Entre un nombre entier de 0 à "+max+".");
    }
    private static TextView button(LinearLayout body,String text){
        android.widget.Button b=new android.widget.Button(body.getContext());b.setText(text);b.setAllCaps(false);body.addView(b);return b;
    }
    private static TextView row(Activity a,LinearLayout body,String title,String[] names,int selected,Choice save){
        TextView button=button(body,title+": "+names[selected]);final int[] current={selected};
        button.setOnClickListener(v->{
            AlertDialog d=new AlertDialog.Builder(MapTheme.wrap(a)).setTitle(title).setSingleChoiceItems(names,current[0],(dialog,index)->{save.set(index);current[0]=index;button.setText(title+": "+names[index]);dialog.dismiss();}).setNegativeButton("Annuler",null).create();
            DialogLifecycle.bind(a,d,null);d.setOnShowListener(x->MapTheme.styleButtons(d));d.show();
        });
        return button;
    }
    private static void input(Activity a,LinearLayout body,String title,String initial,Value save,boolean numeric){
        TextView button=button(body,title+": "+(initial.isEmpty()?"None":initial));final String[] current={initial};
        button.setOnClickListener(v->{
            Context ui=MapTheme.wrap(a);EditText edit=new EditText(ui);edit.setSingleLine(true);edit.setText(current[0]);
            edit.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT);
            AlertDialog d=new AlertDialog.Builder(ui).setTitle(title).setView(edit).setPositiveButton("Enregistrer",null).setNegativeButton("Annuler",null).create();
            DialogLifecycle.bind(a,d,null);d.setOnShowListener(x->{MapTheme.style(edit);MapTheme.styleButtons(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                try{String value=edit.getText().toString();save.set(value);current[0]=numeric?value.trim():normalizeSpecies(value);button.setText(title+": "+(current[0].isEmpty()?"None":current[0]));d.dismiss();}
                catch(IllegalArgumentException e){edit.setError(e.getMessage());}
            });});d.show();
        });
    }
}

/** Feature-specific entry points; all species lists share PokemonSpeciesDialog. */
final class SmartCatchSpeciesDialog {
    private SmartCatchSpeciesDialog() {}
    static void show(Activity activity, Runnable onSaved) {
        PokemonSpeciesDialog.show(activity, "Espèces à Bonbons (Nanana)", "species", 200,
            SmartCatchSettings::candySpecies, SmartCatchSettings::setCandySpecies, onSaved,
            "Aucune (pas d'espèce à Bonbons choisie)", null);
    }
    static void showCatchOnly(Activity activity, Runnable onSaved) {
        PokemonSpeciesDialog.show(activity, "Capturer seulement ces espèces", "species", 200,
            SmartCatchSettings::catchSpecies, SmartCatchSettings::setCatchSpecies, onSaved,
            "Toutes les espèces (aucune sélection)", null);
    }
    static void show(Activity activity, String title, String noun, int max,
                     java.util.function.Supplier<String> load, java.util.function.Consumer<String> save, Runnable onSaved) {
        PokemonSpeciesDialog.show(activity, title, noun, max, load, save, onSaved, "Toutes les espèces (aucune sélection)", null);
    }
}
