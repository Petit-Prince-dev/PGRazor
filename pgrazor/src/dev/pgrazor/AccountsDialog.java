package dev.pgrazor;

import android.app.*;
import android.widget.*;
import android.view.Gravity;
import java.util.Locale;

/** Account chooser; authentication remains in the game's Google/PTC sign-in flow. */
final class AccountsDialog {
    static void show(Activity activity) {
        android.content.Context ui=MapTheme.wrap(activity);
        LinearLayout body=new LinearLayout(ui);body.setOrientation(1);int pad=MapTheme.dp(activity,16);body.setPadding(pad,pad,pad,pad);
        TextView status=new TextView(ui);status.setText(AccountProfiles.status());status.setTextSize(16);body.addView(status);
        TextView explanation=new TextView(ui);
        explanation.setText("Chaque compte garde ses réglages, sa position et sa connexion enregistrée. Changer de compte relance le jeu. Connecte-toi une fois par compte ; une session expirée peut demander de se reconnecter.");
        explanation.setPadding(0,pad,0,pad);body.addView(explanation);
        ScrollView scroll=new ScrollView(ui);LinearLayout rows=new LinearLayout(ui);rows.setOrientation(1);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        AccountProfileStore store=AccountProfiles.store();
        java.util.ArrayList<Button> switches=new java.util.ArrayList<>();
        java.util.LinkedHashMap<Button,String> deletions=new java.util.LinkedHashMap<>();
        if(store!=null)for(AccountProfileStore.Profile profile:store.list()){
            LinearLayout row=new LinearLayout(ui);row.setOrientation(1);row.setPadding(0,pad/2,0,pad/2);
            TextView label=new TextView(ui);label.setText(profile.label+(profile.id.equals(AccountProfiles.currentId())?" (current)":""));label.setTextSize(17);row.addView(label);
            try{android.util.AtomicFile f=new android.util.AtomicFile(store.dataFile(profile.id,"pgrazor-location-state-v1.txt"));
                LocationState location=LocationState.decode(new String(f.readFully(),java.nio.charset.StandardCharsets.UTF_8));
                TextView coords=new TextView(ui);coords.setText(String.format(Locale.US,"%.5f, %.5f",location.latitude,location.longitude));row.addView(coords);
            }catch(Exception ignored){}
            LinearLayout actions=new LinearLayout(ui);actions.setGravity(Gravity.END);row.addView(actions);
            Button rename=new Button(ui);rename.setText("Renommer");actions.addView(rename);
            rename.setOnClickListener(v->{EditText name=new EditText(ui);name.setSingleLine(true);name.setText(profile.label);new AlertDialog.Builder(ui).setTitle("Nom du compte").setView(name).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",(d,w)->{String value=name.getText().toString();AccountProfiles.rename(profile.id,value,()->label.setText(value));}).show();});
            Button change=new Button(ui);change.setText("Changer");change.setEnabled(AccountProfiles.canSwitch()&&!profile.id.equals(AccountProfiles.currentId()));actions.addView(change);
            if(!profile.id.equals(AccountProfiles.currentId()))switches.add(change);
            change.setOnClickListener(v->confirm(activity,profile));rows.addView(row);
            Button delete=new Button(ui);delete.setText("Supprimer");delete.setEnabled(AccountProfiles.canDelete(profile.id));actions.addView(delete);deletions.put(delete,profile.id);
            delete.setContentDescription(profile.id.equals(AccountProfiles.currentId())?"Switch accounts before deleting the current account":"Supprimer le compte enregistré");
            delete.setOnClickListener(v->new AlertDialog.Builder(ui).setTitle("Supprimer "+profile.label+"?")
                .setMessage("Retirer de cet appareil cette connexion enregistrée, les réglages, la position, les favoris et les routes ? Ton compte Pokémon GO ne sera pas supprimé.")
                .setNegativeButton("Annuler",null).setPositiveButton("Supprimer",(d,w)->AccountProfiles.delete(activity,profile.id,()->{switches.remove(change);deletions.remove(delete);rows.removeView(row);})).show());
        }
        Button add=new Button(ui);add.setText("Ajouter un compte");add.setEnabled(AccountProfiles.canSwitch());body.addView(add);
        Button reset=new Button(ui);reset.setText("Réinitialiser les choix de connexion");body.addView(reset);
        reset.setOnClickListener(v->new AlertDialog.Builder(ui).setTitle("Réinitialiser les choix de connexion ?")
            .setMessage("Le jeu va redémarrer et redemander ta date de naissance. Saisis ta vraie date de naissance pour voir les méthodes de connexion disponibles.")
            .setNegativeButton("Annuler",null).setPositiveButton("Réinitialiser",(d,w)->AccountProfiles.resetSignIn()).show());
        add.setOnClickListener(v->{EditText label=new EditText(ui);label.setSingleLine(true);label.setHint("Nom du compte");new AlertDialog.Builder(ui).setTitle("Ajouter un compte").setMessage("Crée un profil séparé, puis connecte-toi avec Google ou PTC.").setView(label).setNegativeButton("Annuler",null).setPositiveButton("Continuer",(d,w)->{try{String name=label.getText().toString().trim();AccountProfiles.add(activity,name.isEmpty()?"New account":name);}catch(Exception error){Toast.makeText(activity,"Impossible d'ajouter le compte",Toast.LENGTH_SHORT).show();}}).show();});
        AlertDialog dialog=new AlertDialog.Builder(ui).setTitle("Comptes").setView(body).setPositiveButton("Fermer",null).create();
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh=new Runnable(){public void run(){
            if(!dialog.isShowing())return;
            status.setText(AccountProfiles.status());
            boolean enabled=AccountProfiles.canSwitch();add.setEnabled(enabled);
            reset.setEnabled(AccountProfiles.canResetSignIn());reset.setVisibility(enabled?android.view.View.GONE:android.view.View.VISIBLE);
            for(Button button:switches)button.setEnabled(enabled);
            for(java.util.Map.Entry<Button,String> entry:deletions.entrySet())entry.getKey().setEnabled(AccountProfiles.canDelete(entry.getValue()));
            handler.postDelayed(this,500);
        }};
        dialog.setOnShowListener(d->{MapTheme.fullScreen(dialog);MapTheme.style(body);MapTheme.styleButtons(dialog);handler.post(refresh);});
        DialogLifecycle.bind(activity,dialog,()->handler.removeCallbacksAndMessages(null));dialog.show();
    }
    private static void confirm(Activity activity,AccountProfileStore.Profile profile){
        new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle("Passer à "+profile.label+"?")
            .setMessage("Les déplacements et automatisations vont s'arrêter. Le jeu va redémarrer avec la connexion et la position enregistrées de ce compte. Connecte-toi si ce compte n'a pas encore été enregistré.")
            .setNegativeButton("Annuler",null).setPositiveButton("Changer",(d,w)->AccountProfiles.switchTo(activity,profile.id)).show();
    }
}
