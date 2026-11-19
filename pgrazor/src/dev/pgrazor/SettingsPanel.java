package dev.pgrazor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/**
 * The full-screen PGRazor settings body: a home screen of icon rows, one per category, each opening
 * a page that holds that category's switches, checkboxes, status captions and groups, plus their
 * construction and per-frame refresh. Extracted from {@link Pad}, which keeps overlay
 * attachment/lifecycle, the launcher/quick menu and the joystick. This panel never touches those
 * overlays directly; the few cross-cutting actions it triggers go through {@link Host} so Pad's
 * internals stay private. Settings keys and the {@link Control} API are unchanged.
 */
final class SettingsPanel {
    /** Narrow callback surface back into Pad: everything the settings body needs that lives with the overlays/joystick. */
    interface Host {
        /** Full refresh (overlays + this panel); matches Pad's existing single refresh entry point. */
        void refresh();
        /** Re-theme overlays and this panel after a dark/light toggle. */
        void applyTheme();
        /** Release the location joystick, optionally preserving an in-progress walk. */
        void releaseJoystick(boolean preserveWalk);
        /** Persist the joystick-visible choice and reflect it on the overlay Pad owns. */
        void setJoystickVisible(boolean visible);
        /** Open the shared World map (also used by the quick menu). */
        void openMap();
        /** Open the coordinates & speed editor (tied to engine/joystick state Pad owns). */
        void editLocation();
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.List<AlertDialog> itemDialogs = new java.util.ArrayList<>();
    private final LinearLayout panel, content, body, home;
    private final ScrollView bodyScroll;
    private TextView coords, speedReadout, homeStatus, homeCounts;
    private View closeIcon, backIcon;
    private TextView title, versionLabel, discord;
    private SettingsUi.Dot homeDot;
    private int lastContentWidth=-1;
    // Category navigation: one home row and one page per category; current is -1 on the home screen.
    private final java.util.List<LinearLayout> pages=new java.util.ArrayList<>(), homeRows=new java.util.ArrayList<>();
    private final java.util.List<RowList> rowLists=new java.util.ArrayList<>();
    private final java.util.List<String> pageTitles=new java.util.ArrayList<>(), pageShorts=new java.util.ArrayList<>();
    private final java.util.List<Integer> pageIcons=new java.util.ArrayList<>();
    private LinearLayout telemetry;
    private final java.util.List<TextView> pageSummaries=new java.util.ArrayList<>(), homeNames=new java.util.ArrayList<>();
    private final java.util.List<MenuIcon> homeIcons=new java.util.ArrayList<>();
    private int current=-1, homeScroll;
    private Switch incubatorSwitch;
    private Button incubatorPriority;
    private Button uiScale;
    private final java.util.List<TextView> themeChips=new java.util.ArrayList<>();
    private TextView incubatorStatus;
    // Primary action and cycle/dialog rows.
    private Button mapTap, throwGrade, throwVariety, storageIv, ivCatchMode, ivOwnedMode, itemSettings, cameraSample, interactionCapture, transferKeepList;
    // Evolve-before-transfer rows: enable switch, stages cycle, selection-mode cycle, selected-species picker.
    private Switch evolveTransferSwitch;
    private Button evolveTransferStages, evolveTransferMode, evolveTransferList;
    // Boolean settings use proper switches.
    private Switch idleWalkSwitch, locationSwitch, vgpSwitch, joystickSwitch, cooldownSwitch, localTimeSwitch, encounterCounterSwitch, encounterOverlaySwitch, spawnSwitch, autoTransferSwitch,
        quickSpinSwitch, berrySwitch, quickSummarySwitch, curveSwitch, alwaysHitSwitch, arSwitch, transferOverlaySwitch, catchChanceSwitch, autoBallSwitch, autoThrowSwitch, autoThrowMapSwitch, skipCutscenesSwitch, routeGiftSwitch,
        batteryResSwitch, batteryFpsSwitch, batteryQualitySwitch;
    private Button autoBallTarget, berryPriority;
    private BerryPriorityPicker berryPicker;
    private TextView quickSummaryLabel, vgpStatus, cutsceneStatus, healStatus, smartBattleStatus;
    private Switch healSwitch, healReviveSwitch, smartBattleSwitch, gymRepeatSwitch;
    private Button healWhen, healThreshold, healItems;
    private final CompoundButton[] vgpChoices=new CompoundButton[3];
    private Switch vgpCooldownStop;
    // Status / caption lines (muted, readiness driven).
    private TextView state, steeringStatus, spawnStatus, quickSummaryStatus, transferStatus, quickSpinStatus,
        buddyStatus, giftStatus, itemStatus, rocketStatus, battleStatus, quickBallStatus, ivStatus, cameraStatus, interactionStatus;
    // Multi-option groups: one switch per bit of the option mask.
    private final CompoundButton[] transferProtection=new CompoundButton[8];
    private final Switch[] pvpKeep=new Switch[3];
    private final Button[] pvpRank=new Button[3];
    private final CompoundButton[] giftChoices=new CompoundButton[3];
    private final CompoundButton[] quickMenuChoices=new CompoundButton[QuickMenu.CUSTOMIZABLE.length];
    private final CompoundButton[] rocketChoices=new CompoundButton[3];
    private final CompoundButton[] battleChoices=new CompoundButton[10];
    private Runnable buddyRefresh=()->{},smartRefresh=()->{};
    // PGRazor Root updates (rooted builds only): status line read from the root helper, throttled.
    // Groups gated by backend readiness (catch-summary support, ball-animation support), refreshed without
    // recreation, and the per-page notes shown while some of them are hidden.
    private final java.util.List<View> summaryGated=new java.util.ArrayList<>(), ballGated=new java.util.ArrayList<>();
    private final java.util.List<TextView> summaryNotes=new java.util.ArrayList<>(), ballNotes=new java.util.ArrayList<>();
    private LinearLayout cameraRoot, interactionRoot;
    private final java.util.List<TextView> groupHeads=new java.util.ArrayList<>();
    private final java.util.List<TextView> captions=new java.util.ArrayList<>();

    private int dp(float x){return SettingsUi.dp(activity,x);}
    private int ink(){return SettingsUi.ink();}
    private int muted(){return SettingsUi.muted();}
    private int accent(){return SettingsUi.accent();}
    private GradientDrawable rounded(int color,int radius){return SettingsUi.rounded(activity,color,radius);}
    private void styleTree(View view){SettingsUi.styleTree(activity,view);}
    private Context dialogContext(){return SettingsUi.dialogContext(activity);}
    private TextView text(String label,int size,int color){return SettingsUi.text(activity,label,size,color);}
    private Button button(String label){return SettingsUi.button(activity,label);}

    /** Compares before setting, so a 100 ms refresh tick does no text work and allocates nothing. */
    private static void set(TextView view,String value){
        if(view==null)return;
        String text=value==null?"":value;
        if(!text.contentEquals(view.getText()))view.setText(text);
    }
    /** A status caption disappears when there is nothing to report instead of leaving a blank strip. */
    private static void status(TextView view,String value){
        if(view==null)return;
        String text=value==null?"":value;
        set(view,text);
        int visibility=text.isEmpty()?View.GONE:View.VISIBLE;
        if(view.getVisibility()!=visibility)view.setVisibility(visibility);
    }

    /** Rows share a card, hairline dividers and flat press feedback. */
    private final class RowList extends LinearLayout {
        RowList(){super(activity);rowLists.add(this);setOrientation(VERTICAL);setShowDividers(SHOW_DIVIDER_MIDDLE);setDividerDrawable(SettingsUi.hairline(activity));setDividerPadding(dp(12));}
        @Override public void addView(View child,int index,ViewGroup.LayoutParams params){
            if(child instanceof SettingsUi.RowButton)((SettingsUi.RowButton)child).setFlat(true);
            super.addView(child,index,params);
        }
    }

    /** A bounded press ripple for whole-row hit targets. */
    private android.graphics.drawable.Drawable rowPress(){
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(SettingsUi.rippleColor()),null,new android.graphics.drawable.ColorDrawable(0xffffffff));
    }

    /** One titled card of rows: a small spaced caps label above a surface with a hairline border. */
    private LinearLayout group(LinearLayout owner,String label){
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        TextView head=text(label.toUpperCase(Locale.FRANCE),11,SettingsUi.faint());head.setTypeface(SettingsUi.medium());head.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams headParams=new LinearLayout.LayoutParams(-1,-2);headParams.topMargin=dp(16);headParams.bottomMargin=dp(6);headParams.leftMargin=dp(6);
        root.addView(head,headParams);
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(SettingsUi.cardBackground(activity));card.setPadding(dp(2),dp(2),dp(2),dp(2));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);
        RowList rows=new RowList();rows.setTag(SettingsUi.TAG_GROUP,label);card.addView(rows);
        root.addView(card);owner.addView(root);groupHeads.add(head);
        return rows;
    }
    private LinearLayout plain(LinearLayout owner){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);owner.addView(v);return v;}
    private Button launch(LinearLayout owner,String label,Runnable action){Button b=button(label);owner.addView(b);b.setOnClickListener(v->action.run());return b;}

    /** A collapsible card. Its header is the whole hit target, so opening it never needs a second tap target. */
    private LinearLayout section(LinearLayout owner,String label,boolean open){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(SettingsUi.cardBackground(activity));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);
        LinearLayout head=new LinearLayout(activity);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);head.setMinimumHeight(dp(46));head.setPadding(dp(14),0,dp(10),0);
        TextView title=text(label,14,ink());head.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
        SettingsUi.Chevron chev=new SettingsUi.Chevron(activity);chev.open=open;head.addView(chev,new LinearLayout.LayoutParams(dp(24),dp(24)));
        RowList rows=new RowList();rows.setVisibility(open?View.VISIBLE:View.GONE);rows.setPadding(dp(2),0,dp(2),dp(2));
        rows.setTag(SettingsUi.TAG_SECTION_HEAD,head);rows.setTag(SettingsUi.TAG_GROUP,label);
        head.setOnClickListener(v->{boolean o=rows.getVisibility()!=View.VISIBLE;rows.setVisibility(o?View.VISIBLE:View.GONE);chev.open=o;chev.invalidate();});
        card.addView(head);card.addView(rows);
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(16);
        owner.addView(card,cardParams);
        return rows;
    }

    private Switch switchRow(LinearLayout owner,String label){return switchRow(owner,label,null);}
    /**
     * A setting that is on or off: its label (and an optional one-line hint under it) on the left, the
     * switch on the right. The whole row toggles it, not only the small switch.
     */
    private Switch switchRow(LinearLayout owner,String label,String hint){
        LinearLayout r=new LinearLayout(activity);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(46));r.setPadding(dp(12),dp(6),dp(8),dp(6));
        LinearLayout texts=new LinearLayout(activity);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(0,0,dp(8),0);
        TextView t=text(label,14,ink());texts.addView(t);
        if(hint!=null&&!hint.isEmpty()){TextView h=text(hint,12,muted());h.setLineSpacing(0,1.1f);h.setPadding(0,dp(1),0,0);texts.addView(h);captions.add(h);}
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        Switch sw=new Switch(activity);sw.setContentDescription(label);sw.setClickable(true);r.addView(sw,new LinearLayout.LayoutParams(-2,-2));
        r.setClickable(true);r.setFocusable(true);r.setBackground(rowPress());r.setOnClickListener(v->{if(sw.isEnabled())sw.toggle();});
        owner.addView(r);return sw;
    }
    /** A switch bound to one bit of an option mask, kept in step by the refresh tick. */
    private Switch bitRow(LinearLayout owner,String label,int bit,java.util.function.IntSupplier get,java.util.function.IntConsumer put){
        Switch sw=switchRow(owner,label);sw.setChecked((get.getAsInt()&bit)!=0);
        sw.setOnCheckedChangeListener((v,checked)->{int value=get.getAsInt();if(((value&bit)!=0)==checked)return;put.accept(checked?value|bit:value&~bit);});
        return sw;
    }
    private TextView caption(LinearLayout owner,String s){
        TextView t=text(s,12,muted());t.setLineSpacing(0,1.2f);t.setPadding(dp(12),dp(4),dp(12),dp(8));
        owner.addView(t);captions.add(t);return t;
    }

    /** A clean close affix: a small X drawn inside a 44dp hitbox. */
    private final class CloseIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseIcon(Context c){super(c);setContentDescription("Fermer les réglages PGRazor");setFocusable(true);setClickable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3345e1ba),null,rounded(0xffffffff,22)));}
        protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=dp(7);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(2));paint.setColor(muted());c.drawLine(cx-r,cy-r,cx+r,cy+r,paint);c.drawLine(cx-r,cy+r,cx+r,cy-r,paint);}
    }

    /** A drawn back chevron inside a 44dp hitbox; shown only while a category page is open. */
    private final class BackIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BackIcon(Context c){super(c);setContentDescription("Retour à l'accueil des réglages");setFocusable(true);setClickable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3345e1ba),null,rounded(0xffffffff,22)));}
        protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=dp(7);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(dp(2));paint.setColor(accent());
            Path p=new Path();p.moveTo(cx+r/2,cy-r);p.lineTo(cx-r/2,cy);p.lineTo(cx+r/2,cy+r);c.drawPath(p,paint);}
    }

    /**
     * Adds a category: a tile on the home grid (short name, live summary) and the page it opens.
     * Content is built directly into the returned page container.
     */
    private LinearLayout page(int icon,String shortName,String pageTitle){
        final int index=pages.size();
        LinearLayout tile=new LinearLayout(activity);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4),dp(12),dp(4),dp(10));tile.setClickable(true);tile.setFocusable(true);tile.setContentDescription(pageTitle);
        tile.setBackground(SettingsUi.ripple(SettingsUi.cardBackground(activity)));
        LinearLayout disc=new LinearLayout(activity);disc.setGravity(Gravity.CENTER);disc.setBackground(SettingsUi.rounded(activity,SettingsUi.accentSoft(),12));
        MenuIcon glyph=new MenuIcon(activity,icon);glyph.accent=true;disc.addView(glyph,new LinearLayout.LayoutParams(dp(20),dp(20)));
        tile.addView(disc,new LinearLayout.LayoutParams(dp(40),dp(40)));
        TextView name=text(shortName,13,ink());name.setTypeface(SettingsUi.medium());name.setGravity(Gravity.CENTER);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(0,dp(8),0,0);
        tile.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView summary=text("",11,muted());summary.setGravity(Gravity.CENTER);summary.setSingleLine(true);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);summary.setPadding(0,dp(2),0,0);
        tile.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        tile.setOnClickListener(v->open(index));
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setVisibility(View.GONE);content.setPadding(0,0,0,dp(24));body.addView(content);
        pages.add(content);pageTitles.add(pageTitle);pageShorts.add(shortName);pageIcons.add(icon);homeRows.add(tile);homeNames.add(name);homeIcons.add(glyph);pageSummaries.add(summary);
        captions.add(summary);
        return content;
    }
    /** Lays the category tiles out three per row under the status card. */
    private void layoutHomeGrid(){
        LinearLayout grid=new LinearLayout(activity);grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row=null;
        for(int i=0;i<homeRows.size();i++){
            if(i%3==0){row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);grid.addView(row,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(homeRows.get(i),lp);
        }
        if(row!=null)for(int i=homeRows.size()%3;i>0&&i<3;i++)row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1f));
        LinearLayout.LayoutParams gridParams=new LinearLayout.LayoutParams(-1,-2);gridParams.topMargin=dp(9);gridParams.leftMargin=-dp(3);gridParams.rightMargin=-dp(3);
        home.addView(grid,gridParams);
    }
    /** Shown at the top of a page while some of its groups are hidden because the game has not reported support. */
    private void unavailableNote(LinearLayout owner,boolean needsBall){
        TextView note=caption(owner,"Certains réglages apparaîtront quand le jeu sera prêt.");
        (needsBall?ballNotes:summaryNotes).add(note);
    }
    /** Shows the home screen (index -1) or one category page, keeping the home scroll position for the way back. */
    private void open(int index){
        hideKeyboard();
        index=Math.max(0,Math.min(pages.size()-1,index));
        current=index;refreshHome();
        home.setVisibility(View.GONE);
        for(int i=0;i<pages.size();i++)pages.get(i).setVisibility(i==index?View.VISIBLE:View.GONE);
        set(title,pageTitles.get(index));
        backIcon.setVisibility(View.GONE);
        versionLabel.setVisibility(View.GONE);
        bodyScroll.post(()->bodyScroll.scrollTo(0,0));
    }

    SettingsPanel(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundColor(scrim());
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(SettingsUi.cardBackground(activity)); content.setPadding(dp(10),dp(4),dp(10),dp(10));
        panel.addView(content,new LinearLayout.LayoutParams(-1,-1));
        // Header: back (on a category page), title, version (home only) and close.
        LinearLayout header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        backIcon = new BackIcon(activity); backIcon.setVisibility(View.GONE); header.addView(backIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = text("PGRazor", 18, ink()); title.setTypeface(SettingsUi.medium()); title.setPadding(dp(4), 0, 0, 0);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        versionLabel = text(BuildVersion.NAME, 11, SettingsUi.faint());
        versionLabel.setTypeface(SettingsUi.mono());
        versionLabel.setSingleLine(true); versionLabel.setGravity(Gravity.CENTER_VERTICAL);
        versionLabel.setPadding(dp(4), 0, dp(4), 0);
        versionLabel.setContentDescription("Version PGRazor " + BuildVersion.NAME);
        captions.add(versionLabel);
        header.addView(versionLabel, new LinearLayout.LayoutParams(-2, dp(44)));
        closeIcon = new CloseIcon(activity); header.addView(closeIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(header);

        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        bodyScroll = new ScrollView(activity); bodyScroll.setFillViewport(false); bodyScroll.setVerticalScrollBarEnabled(false);
        bodyScroll.addView(body, new ScrollView.LayoutParams(-1,-2));
        content.addView(bodyScroll, new LinearLayout.LayoutParams(-1,0,1));

        // Home: the live status card, then the category grid; the community link closes the home screen.
        home = new LinearLayout(activity); home.setOrientation(LinearLayout.VERTICAL); body.addView(home);
        buildLocationPage();
        buildTelemetryCard();
        buildCatchingPage();
        buildPokemonPage();
        buildGoPlusPage();
        buildBattlesPage();
        buildSocialPage();
        buildAutomationPage();
        buildDisplayPage();
        buildAccountsPage();   // last: its Diagnostics group collects status lines created by the pages above
        home.setVisibility(View.GONE); // the categories live in the R menu

        // Closing the full-screen settings panel simply returns to the game (or the quick menu on the next tap).
        closeIcon.setOnClickListener(v -> { hideKeyboard(); hide(); });
        // Taps on the dimmed backdrop do nothing: the sheet closes with its X only.
        // The settings panel is a full-screen modal; it consumes taps so the game behind it stays inert.
        panel.setClickable(true);
        // The column stops growing on tablets and stays centred instead of stretching rows across the screen.
        panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->updateContentWidth());
        updateContentWidth();
    }

    private void updateContentWidth(){
        int width=panel.getWidth()-panel.getPaddingLeft()-panel.getPaddingRight();
        if(width<=0)return;
        int extra=Math.max(0,(width-dp(SettingsUi.CONTENT_MAX_DP))/2);
        if(extra==lastContentWidth)return;
        lastContentWidth=extra;
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)content.getLayoutParams();lp.leftMargin=extra;lp.rightMargin=extra;content.setLayoutParams(lp);
    }

    private void hideKeyboard(){
        try{
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(panel.getWindowToken(),0);
        }catch(RuntimeException ignored){}
    }

    /** The home status card: spoofing state and speed, coordinates, then encounters and cooldown. */
    private void buildTelemetryCard(){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SettingsUi.cardBackground(activity));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);card.setPadding(dp(14),dp(10),dp(14),dp(10));
        LinearLayout top=new LinearLayout(activity);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
        homeDot=new SettingsUi.Dot(activity,SettingsUi.success());top.addView(homeDot,new LinearLayout.LayoutParams(dp(8),dp(8)));
        homeStatus=text("Démarrage",13,ink());homeStatus.setTypeface(SettingsUi.medium());homeStatus.setSingleLine(true);homeStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1f);statusParams.leftMargin=dp(8);top.addView(homeStatus,statusParams);
        speedReadout=text(SettingsUi.speedLabel(0),12,accent());speedReadout.setTypeface(SettingsUi.mono());top.addView(speedReadout);
        card.addView(top);
        coords=text(String.format(Locale.US,"%.6f, %.6f",Control.engine.latitude,Control.engine.longitude),12,muted());coords.setTypeface(SettingsUi.mono());coords.setSingleLine(true);
        LinearLayout.LayoutParams coordsParams=new LinearLayout.LayoutParams(-1,-2);coordsParams.topMargin=dp(6);card.addView(coords,coordsParams);
        homeCounts=text("",12,muted());homeCounts.setSingleLine(true);homeCounts.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams countsParams=new LinearLayout.LayoutParams(-1,-2);countsParams.topMargin=dp(2);card.addView(homeCounts,countsParams);captions.add(homeCounts);
        telemetry=card;
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(6);
        pages.get(0).addView(card,0,cardParams);
    }

    private void buildLocationPage() {
        LinearLayout loc = page(MenuIcon.PIN, "Position", "Position & déplacement");
        LinearLayout core = group(loc, "Spoof");
        locationSwitch=switchRow(core,"Spoof de position");locationSwitch.setChecked(Control.desiredEnabled());
        locationSwitch.setOnCheckedChangeListener((v,checked)->{if(checked==Control.desiredEnabled())return;host.releaseJoystick(true);Control.setLocationSpoofing(checked);host.refresh();});
        steeringStatus = caption(core, "Le joystick suit la caméra");
        launch(core, "Coordonnées & vitesse", host::editLocation);
        launch(core, "Carte du monde", host::openMap);
        joystickSwitch = switchRow(core, "Joystick à l'écran"); joystickSwitch.setChecked(Control.joystickVisible());
        joystickSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.joystickVisible())return; host.setJoystickVisible(checked); });
        idleWalkSwitch = switchRow(core, "Marche à l'arrêt", "Tourne en rond quand tu ne bouges pas. Vitesse 0 = pause.");idleWalkSwitch.setChecked(Control.idleWalking());
        idleWalkSwitch.setOnCheckedChangeListener((v,checked)->{if(checked!=Control.idleWalking())Control.setIdleWalking(checked);});

        LinearLayout map = group(loc, "Carte du jeu");
        mapTap = button("Appui sur la carte : ne rien faire"); map.addView(mapTap);
        mapTap.setOnClickListener(v -> { Control.setMapTapMode((Control.mapTapMode()+1)%4); host.refresh(); });
        spawnSwitch = switchRow(map, "Booster d'apparitions", "Rayon de 80 m. Peut faire ramer dans les zones chargées."); spawnSwitch.setChecked(Control.spawnBooster());
        spawnSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.spawnBooster())return; Control.setSpawnBooster(spawnSwitch.isChecked()); });
        spawnStatus = caption(map, "");
    }

    private void buildCatchingPage() {
        LinearLayout page = page(MenuIcon.CATCH, "Capture", "Capture");
        unavailableNote(page, true);

        // ----- Quick catch (gated by catch-summary support) -----
        LinearLayout quick = group(page, "Capture rapide"); summaryGated.add((View)quick.getParent());
        LinearLayout sumRow = new LinearLayout(activity); sumRow.setOrientation(LinearLayout.HORIZONTAL); sumRow.setGravity(Gravity.CENTER_VERTICAL); sumRow.setMinimumHeight(dp(46)); sumRow.setPadding(dp(12),0,dp(8),0);
        quickSummaryLabel = new TextView(activity); quickSummaryLabel.setTextSize(14); sumRow.addView(quickSummaryLabel, new LinearLayout.LayoutParams(0,-2,1f));
        quickSummarySwitch = new Switch(activity); quickSummarySwitch.setContentDescription("Capture rapide"); sumRow.addView(quickSummarySwitch, new LinearLayout.LayoutParams(-2,-2)); quick.addView(sumRow);
        sumRow.setClickable(true); sumRow.setBackground(rowPress()); sumRow.setOnClickListener(v -> { if(quickSummarySwitch.isEnabled()) quickSummarySwitch.toggle(); });
        quickSummarySwitch.setChecked(Control.quickSummaryEnabled());
        quickSummarySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.quickSummaryEnabled())return; Control.setQuickSummaryEnabled(quickSummarySwitch.isChecked()); host.refresh(); });
        quickSummaryStatus = caption(quick, "");

        // ----- Throws (gated by ball-animation support) -----
        LinearLayout throwC = group(page, "Lancers"); ballGated.add((View)throwC.getParent());
        throwGrade = button("Qualité du lancer : désactivée"); throwC.addView(throwGrade);
        throwGrade.setOnClickListener(v -> { Control.setThrowGrade((Control.throwRating()+1)%4); host.refresh(); });
        throwVariety = button("Lancers variés : Off"); throwC.addView(throwVariety);
        throwVariety.setOnClickListener(v -> { Control.setThrowVariety((Control.throwVariety()+1)%4); host.refresh(); });
        curveSwitch = switchRow(throwC, "Balle courbe"); curveSwitch.setChecked(Control.throwCurve());
        curveSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwCurve())return; Control.setThrowCurve(curveSwitch.isChecked()); host.refresh(); });
        alwaysHitSwitch = switchRow(throwC, "Toujours toucher"); alwaysHitSwitch.setChecked(Control.throwAlwaysHit());
        alwaysHitSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwAlwaysHit())return; Control.setThrowAlwaysHit(alwaysHitSwitch.isChecked()); host.refresh(); });
        arSwitch = switchRow(throwC, "Bonus RA+", "Rencontres sauvages, en plus de la qualité et de la courbe."); arSwitch.setChecked(Control.throwAr());
        arSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwAr())return; Control.setThrowAr(arSwitch.isChecked()); host.refresh(); });
        caption(throwC, "Lancers variés : une qualité au hasard à chaque lancer, à la place de la qualité fixe.");

        LinearLayout ballC = group(page, "Choix de la Ball"); ballGated.add((View)ballC.getParent());
        catchChanceSwitch = switchRow(ballC, "Afficher la chance de capture"); catchChanceSwitch.setChecked(Control.catchChanceShown());
        catchChanceSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.catchChanceShown())return; Control.setCatchChanceShown(checked); host.refresh(); });
        autoBallSwitch = switchRow(ballC, "Ball auto", "La Ball la moins chère qui atteint l'objectif. Jamais de Master Ball."); autoBallSwitch.setChecked(Control.autoBall());
        autoBallSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoBall())return; Control.setAutoBall(checked); host.refresh(); });
        autoBallTarget = button("Objectif : "+Control.autoBallTarget()+" %"); ballC.addView(autoBallTarget);
        autoBallTarget.setOnClickListener(v -> {
            int[] targets=Control.AUTO_BALL_TARGETS;int next=targets[0];
            for(int i=0;i<targets.length;i++)if(targets[i]==Control.autoBallTarget()){next=targets[(i+1)%targets.length];break;}
            Control.setAutoBallTarget(next); host.refresh(); });

        LinearLayout autoC = group(page, "Lancer auto"); ballGated.add((View)autoC.getParent());
        autoThrowSwitch = switchRow(autoC, "Lancer auto"); autoThrowSwitch.setChecked(Control.autoThrow());
        autoThrowSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoThrow())return; Control.setAutoThrow(autoThrowSwitch.isChecked()); host.refresh(); });
        autoThrowMapSwitch = switchRow(autoC, "Capturer depuis la carte", "Touche le Pokémon sur la carte.");
        autoThrowMapSwitch.setChecked(Control.autoThrowOnMap());
        autoThrowMapSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoThrowOnMap())return; Control.setAutoThrowOnMap(checked); host.refresh(); });
        quickBallStatus = caption(autoC, "");

        LinearLayout berryC = group(page, "Baies"); summaryGated.add((View)berryC.getParent());
        berrySwitch = switchRow(berryC, "Baies auto", "Avec le bouton de baie pendant la rencontre."); berrySwitch.setChecked(Control.encounterBerryEnabled());
        berrySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterBerryEnabled())return; Control.setEncounterBerryEnabled(berrySwitch.isChecked()); host.refresh(); });
        berryPicker = new BerryPriorityPicker(activity, host::refresh);
        berryPriority = button("Priorité : "+BerryPriorityPicker.summary()); berryC.addView(berryPriority);
        berryPriority.setOnClickListener(v -> berryPicker.show());
    }

    private void buildPokemonPage() {
        LinearLayout page = page(MenuIcon.BOX, "Pokémon", "Pokémon & transfert");
        unavailableNote(page, false);

        // ----- Transfer on catch (gated by catch-summary support) -----
        LinearLayout transfer = plain(page); summaryGated.add(transfer);
        LinearLayout txC = group(transfer, "Transfert auto");
        autoTransferSwitch = switchRow(txC, "Transférer sous 90 % d'IV", "Définitif. Favoris et données inconnues toujours gardés."); autoTransferSwitch.setChecked(Control.autoTransferEnabled());
        autoTransferSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoTransferEnabled())return; Control.setAutoTransferEnabled(autoTransferSwitch.isChecked()); host.refresh(); });
        transferStatus = caption(txC, "");

        LinearLayout keepC = group(transfer, "Toujours garder");
        // Indexed by protection bit; costume (bit 7) is listed next to event.
        String[] protections={"Légendaires, fabuleux, Ultra-Chimères","Shiny","Événement","Fond spécial","XXS","XXL","0 % IV (0/0/0)","Costumés"};
        for(int i:new int[]{0,1,2,7,3,4,5,6})transferProtection[i]=bitRow(keepC,protections[i],1<<i,Control::transferProtect,Control::setTransferProtect);
        transferKeepList = button("Ma liste d'espèces"); keepC.addView(transferKeepList);
        transferKeepList.setOnClickListener(v -> TransferKeepDialog.show(activity, host::refresh));

        LinearLayout pvpC = group(transfer, "Garder pour le PvP");
        // One row per league: its switch, then the rank it keeps (tap to cycle).
        for(int i=0;i<3;i++){final int league=i;
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(46));row.setPadding(dp(12),0,dp(8),0);
            TextView label=new TextView(activity);label.setText(Control.PVP_LEAGUES[i]);label.setTextSize(14);row.addView(label,new LinearLayout.LayoutParams(0,-2,1f));
            Button rank=button("Top "+Control.pvpRank(i));pvpRank[i]=rank;rank.setMinWidth(0);rank.setMinimumWidth(0);row.addView(rank,new LinearLayout.LayoutParams(-2,dp(36)));
            Switch sw=new Switch(activity);sw.setContentDescription("Garder les IV PvP "+Control.PVP_LEAGUES[i]);sw.setChecked(Control.pvpKeep(i));pvpKeep[i]=sw;
            LinearLayout.LayoutParams swParams=new LinearLayout.LayoutParams(-2,-2);swParams.leftMargin=dp(8);row.addView(sw,swParams);
            sw.setOnCheckedChangeListener((v,checked)->{if(checked==Control.pvpKeep(league))return;Control.setPvpKeep(league,checked);host.refresh();});
            rank.setOnClickListener(v->{
                int[] ranks=Control.PVP_RANKS;int next=ranks[0];
                for(int j=0;j<ranks.length;j++)if(ranks[j]==Control.pvpRank(league)){next=ranks[(j+1)%ranks.length];break;}
                Control.setPvpRank(league,next);host.refresh();});
            pvpC.addView(row);}
        caption(pvpC, "Garde les IV du top choisi pour la ligue (espèces du top "+Control.PVP_META+" PvPoke, sous la limite de PC, jusqu'au niveau 50).");

        LinearLayout evolveC = group(transfer, "Évoluer avant de transférer");
        evolveTransferSwitch = switchRow(evolveC, "Faire évoluer d'abord", "Nouvelles captures, évolutions simples avec Bonbons. Sinon, transfert normal.");
        evolveTransferSwitch.setChecked(Control.evolveTransferEnabled());
        evolveTransferSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.evolveTransferEnabled())return; Control.setEvolveTransferEnabled(evolveTransferSwitch.isChecked()); host.refresh(); });
        evolveTransferStages = button("Évolution : une fois"); evolveC.addView(evolveTransferStages);
        evolveTransferStages.setOnClickListener(v -> { Control.setEvolveTransferStages(Control.evolveTransferStages()==2?1:2); host.refresh(); });
        evolveTransferMode = button("Pour : règles de transfert"); evolveC.addView(evolveTransferMode);
        evolveTransferMode.setOnClickListener(v -> { Control.setEvolveTransferMode(Control.evolveTransferMode()==1?0:1); host.refresh(); });
        evolveTransferList = button("Espèces choisies"); evolveC.addView(evolveTransferList);
        evolveTransferList.setOnClickListener(v -> TransferKeepDialog.show(activity, "Espèces choisies", "espèces choisies", Control::evolveTransferSpecies, Control::setEvolveTransferSpecies, host::refresh));

        // ----- IV display: the encounter, Pokemon details and storage list IV overlays -----
        LinearLayout iv = group(page, "Affichage des IV");
        // One button per screen; each tap moves to the next format, with Off between the last and first.
        ivCatchMode = button(""); iv.addView(ivCatchMode);
        ivCatchMode.setOnClickListener(v -> { Control.setIvCatchMode(Control.nextIvMode(Control.ivCatchMode())); host.refresh(); });
        ivOwnedMode = button(""); iv.addView(ivOwnedMode);
        ivOwnedMode.setOnClickListener(v -> { Control.setIvOwnedMode(Control.nextIvMode(Control.ivOwnedMode())); host.refresh(); });
        storageIv = button(""); iv.addView(storageIv);
        storageIv.setOnClickListener(v -> { Control.setStorageIvMode(Control.nextIvMode(Control.storageIvMode())); host.refresh(); });
        ivStatus = caption(iv, "");

        LinearLayout tools = group(page, "Outils");
        launch(tools, "Évolution en masse", () -> BatchEvolutionSettings.show(activity));
    }

    private void buildGoPlusPage() {
        LinearLayout page = page(MenuIcon.BAG, "Go Plus", "Go Plus, PokéStops & objets");
        unavailableNote(page, false);

        LinearLayout vgpC=group(page,"Go Plus virtuel");
        vgpSwitch=switchRow(vgpC,"Go Plus virtuel","Bouton VGP dans le panneau sur la carte.");vgpSwitch.setChecked(Control.vgpOverlay());
        vgpSwitch.setOnCheckedChangeListener((b,checked)->{if(checked!=Control.vgpOverlay())Control.setVgpOverlay(checked);host.refresh();});
        String[] vgpLabels={"Pokémon proches","PokéStops proches","Notifications"};
        for(int i=0;i<3;i++){final int bit=1<<i;Switch box=bitRow(vgpC,vgpLabels[i],bit,Control::vgpOptions,Control::setVgpOptions);vgpChoices[i]=box;
            box.setOnCheckedChangeListener((v,checked)->{int value=Control.vgpOptions();if(((value&bit)!=0)==checked)return;Control.setVgpOptions(checked?value|bit:value&~bit);
                if(bit==4&&checked&&Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},7301);
            });
        }
        vgpCooldownStop=switchRow(vgpC,"Pause pendant le cooldown");vgpCooldownStop.setContentDescription("Mettre le Go Plus virtuel en pause pendant le cooldown estimé");vgpCooldownStop.setChecked(Control.vgpStopOnCooldown());
        vgpCooldownStop.setOnCheckedChangeListener((v,checked)->{if(checked!=Control.vgpStopOnCooldown())Control.setVgpStopOnCooldown(checked);host.refresh();});
        vgpStatus=caption(vgpC,Control.vgpStatus());
        smartRefresh=SmartCatchSettings.populate(activity,vgpC);

        LinearLayout stops = group(page, "PokéStops"); summaryGated.add((View)stops.getParent());
        quickSpinSwitch = switchRow(stops, "Spin rapide"); quickSpinSwitch.setChecked(Control.quickSpinEnabled());
        quickSpinSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.quickSpinEnabled())return; Control.setQuickSpinEnabled(quickSpinSwitch.isChecked()); });
        quickSpinStatus = caption(stops, "");

        LinearLayout routesC = group(page, "Routes"); summaryGated.add((View)routesC.getParent());
        routeGiftSwitch = switchRow(routesC, "Cadeaux avec Mateo", "Sur une Route : échange et ouvre les cadeaux à ta place. Les échanges de Pokémon restent manuels."); routeGiftSwitch.setChecked(Control.routeGiftExchange());
        routeGiftSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.routeGiftExchange())return; Control.setRouteGiftExchange(checked); });

        // ----- Inventory (opens its modal directly) -----
        LinearLayout itemsC = group(page, "Objets"); summaryGated.add((View)itemsC.getParent());
        itemSettings = button("Tri auto des objets"); itemsC.addView(itemSettings); itemSettings.setOnClickListener(v -> showItemSettings());
        itemStatus = caption(itemsC, "");
    }

    private void buildBattlesPage() {
        LinearLayout page = page(MenuIcon.BATTLE, "Combats", "Combats");
        unavailableNote(page, false);
        LinearLayout battleC = plain(page); summaryGated.add(battleC);
        LinearLayout all = group(battleC, "Tous les combats");
        buildSmartBattle(all);
        buildHealing(all);

        LinearLayout gymC = group(battleC, "Arènes");
        launch(gymC, "Placement & nourrissage", () -> GymAutomationSettings.show(activity));
        launch(gymC, "Nourrir à distance", () -> AutomationPanel.showRemoteFeed(activity));
        battleChoice(gymC,2,"Attaque rapide auto");
        battleChoice(gymC,3,"Attaque chargée auto");
        battleChoice(gymC,8,"Esquive auto");
        gymRepeatSwitch = switchRow(gymC, "Combattre jusqu'à la défaite", "Relance la manche suivante depuis la même arène."); gymRepeatSwitch.setChecked(Control.gymRepeatEnabled());
        gymRepeatSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.gymRepeatEnabled())return; Control.setGymRepeatEnabled(checked); });

        LinearLayout raidC = group(battleC, "Raids");
        launch(raidC, "Auto-Raid", () -> AutoRaidDialog.show(activity));
        battleChoice(raidC,0,"Attaque rapide auto");
        battleChoice(raidC,1,"Attaque chargée auto");
        battleChoice(raidC,7,"Esquive auto");

        LinearLayout maxC = group(battleC, "Dynamax");
        battleChoice(maxC,4,"Attaque rapide auto");
        battleChoice(maxC,5,"Attaque chargée auto");
        battleChoice(maxC,6,"Attaque Max auto");
        battleChoice(maxC,9,"Esquive auto");
        caption(maxC,"Va chercher les particules Max proches pendant le combat.");

        // ----- Team Rocket -----
        LinearLayout rocketC = group(battleC, "Team GO Rocket");
        String[] rocketLabels={"Attaque rapide auto","Attaque chargée auto","Bouclier auto"};
        for(int i=0;i<3;i++)rocketChoices[i]=bitRow(rocketC,rocketLabels[i],1<<i,Control::rocketOptions,Control::setRocketOptions);
        caption(rocketC, "Le Pokémon suivant est choisi tout seul quand l'un est K.O.");
        rocketStatus = caption(rocketC, "");
        battleStatus = caption(battleC, "");
    }

    private void buildSmartBattle(LinearLayout owner) {
        smartBattleSwitch = switchRow(owner, "Combat intelligent", "Choisit tes meilleurs counters avant et pendant les combats."); smartBattleSwitch.setChecked(Control.smartBattle());
        smartBattleSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.smartBattle())return; Control.setSmartBattle(checked); refreshSmartBattle(); });
        smartBattleStatus = caption(owner, "");
        refreshSmartBattle();
    }

    private void refreshSmartBattle() {
        if(smartBattleSwitch==null)return;
        smartBattleSwitch.setChecked(Control.smartBattle());
        status(smartBattleStatus,Control.smartBattleStatus());
    }

    private void buildHealing(LinearLayout owner) {
        healSwitch = switchRow(owner, "Soin intelligent"); healSwitch.setChecked(Control.healEnabled());
        healSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.healEnabled())return; Control.setHealEnabled(checked); refreshHealing(); });
        healWhen = button(""); owner.addView(healWhen);
        healWhen.setOnClickListener(v -> { Control.setHealWhen((Control.healWhen()+1)%3); refreshHealing(); });
        healThreshold = button(""); owner.addView(healThreshold);
        healThreshold.setOnClickListener(v -> { Control.cycleHealThreshold(); refreshHealing(); });
        healItems = button(""); owner.addView(healItems);
        healItems.setOnClickListener(v -> { Control.setHealFull(!Control.healFull()); refreshHealing(); });
        healReviveSwitch = switchRow(owner, "Ranimer les K.O."); healReviveSwitch.setChecked(Control.healRevive());
        healReviveSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.healRevive())return; Control.setHealRevive(checked); });
        healStatus = caption(owner, "");
        refreshHealing();
    }

    private void refreshHealing() {
        if(healSwitch==null)return;
        healSwitch.setChecked(Control.healEnabled()); healReviveSwitch.setChecked(Control.healRevive());
        set(healWhen,"Quand : "+Control.HEAL_WHEN_LABELS[Control.healWhen()]);
        set(healThreshold,Control.healThreshold()>=100?"Seuil : dès qu'il manque des PV":"Seuil : sous "+Control.healThreshold()+" % de PV");
        set(healItems,Control.healFull()?"Objets : soin complet":"Objets : économe (garde les grosses potions)");
        boolean on=Control.healEnabled();
        SettingsUi.enabled(healWhen,on); SettingsUi.enabled(healThreshold,on); SettingsUi.enabled(healItems,on); SettingsUi.enabled((View)healReviveSwitch.getParent(),on);
        status(healStatus,Control.healStatus());
    }

    private void battleChoice(LinearLayout owner,int index,String label) {
        // Keep the persisted/native option bits unchanged when moving controls between groups.
        battleChoices[index]=bitRow(owner,label,1<<index,Control::battleOptions,Control::setBattleOptions);
    }

    private void buildSocialPage() {
        LinearLayout page = page(MenuIcon.HEART, "Copain", "Copain, œufs & cadeaux");
        unavailableNote(page, false);

        LinearLayout buddyC=group(page,"Humeur du copain"); summaryGated.add((View)buddyC.getParent());
        buddyRefresh=BuddyEmotionSettings.populate(activity,buddyC);
        buddyStatus=caption(buddyC,"");

        LinearLayout eggsC = group(page, "Œufs");
        incubatorSwitch = switchRow(eggsC, "Incubation auto", "Incubateur illimité uniquement.");
        incubatorSwitch.setChecked(Control.autoIncubate());
        incubatorSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (checked != Control.autoIncubate()) Control.setAutoIncubate(checked);
        });
        incubatorPriority = button("Œuf prioritaire : le plus court"); eggsC.addView(incubatorPriority);
        incubatorPriority.setOnClickListener(v -> { Control.setIncubateLongest(!Control.incubateLongest()); host.refresh(); });
        incubatorStatus = caption(eggsC, Control.incubatorStatus());

        LinearLayout giftC = group(page, "Cadeaux d'amis"); summaryGated.add((View)giftC.getParent());
        launch(giftC, "Envoyer & ouvrir maintenant", () -> GiftBatchDialog.show(activity));
        String[] giftLabels={"Ouvrir les cadeaux auto","Envoyer les cadeaux auto","Ajouter un autocollant"};
        for(int i=0;i<3;i++)giftChoices[i]=bitRow(giftC,giftLabels[i],1<<i,Control::giftOptions,Control::setGiftOptions);
        giftStatus = caption(giftC, "");
    }

    private void buildAutomationPage() {
        // One-shot tasks you start yourself, field research and the temporary event settings.
        LinearLayout page = page(MenuIcon.AUTOMATION, "Tâches", "Tâches & événements");
        LinearLayout tasks = group(page, "Tâches à lancer");
        launch(tasks, "Shundo Sniper (bêta)", () -> ShundoSniper.show(activity));
        launch(tasks, "Scan shiny (bêta)", () -> ShinyScanner.show(activity));
        launch(tasks, "Auto-Raid", () -> AutoRaidDialog.show(activity));
        launch(tasks, "Session d'Encens du jour", () -> AutomationPanel.showIncense(activity));
        launch(tasks, "Nourrir les arènes à distance", () -> AutomationPanel.showRemoteFeed(activity));
        launch(tasks, "Envoyer & ouvrir les cadeaux", () -> GiftBatchDialog.show(activity));
        launch(tasks, "Évolution en masse", () -> BatchEvolutionSettings.show(activity));
        LinearLayout research = group(page, "Études de terrain");
        launch(research, "Études", () -> ResearchSettings.show(activity));
        LinearLayout events = group(page, "Événements");
        launch(events, "Halloween 2026", () -> dev.pgrazor.events.EventPanel.showHalloween(activity, Control.darkTheme()));
        launch(events, "Go Wild November 2026", () -> dev.pgrazor.events.EventPanel.showGoWild(activity, Control.darkTheme()));
    }

    private void buildDisplayPage() {
        LinearLayout page = page(MenuIcon.SETTINGS, "Affichage", "Affichage & batterie");
        // Everything the map panel (the card under the R) can show, in one place.
        LinearLayout hud = group(page, "Panneau sur la carte");
        cooldownSwitch = switchRow(hud, "Cooldown", "Temps estimé avant de pouvoir rejouer sans risque."); cooldownSwitch.setChecked(Control.cooldownOverlay());
        cooldownSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.cooldownOverlay())return; Control.setCooldownOverlay(cooldownSwitch.isChecked()); host.refresh(); });
        launch(hud, "Historique du cooldown", () -> Cooldown.show(activity));
        encounterCounterSwitch = switchRow(hud, "Compter les rencontres", "Sauvages, sur 24 h, sur cet appareil (hors Leurre, Encens, raids et études)."); encounterCounterSwitch.setChecked(Control.encounterCounter());
        encounterCounterSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterCounter())return; Control.setEncounterCounter(encounterCounterSwitch.isChecked()); host.refresh(); });
        encounterOverlaySwitch = switchRow(hud, "Rencontres dans le panneau"); encounterOverlaySwitch.setChecked(Control.encounterOverlay());
        encounterOverlaySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterOverlay())return; Control.setEncounterOverlay(encounterOverlaySwitch.isChecked()); host.refresh(); });
        localTimeSwitch = switchRow(hud, "Heure locale", "L'heure à ta position spoofée."); localTimeSwitch.setChecked(Control.localTimeOverlay());
        localTimeSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.localTimeOverlay())return; Control.setLocalTimeOverlay(localTimeSwitch.isChecked()); host.refresh(); });
        transferOverlaySwitch = switchRow(hud, "Bouton « Tout garder »", "Garde chaque capture ou suit tes règles de transfert. Demande le transfert auto."); transferOverlaySwitch.setChecked(Control.transferOverlayEnabled());
        transferOverlaySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.transferOverlayEnabled())return; Control.setTransferOverlayEnabled(transferOverlaySwitch.isChecked()); host.refresh(); });

        LinearLayout look = group(page, "Interface");
        LinearLayout themeRow=new LinearLayout(activity);themeRow.setOrientation(LinearLayout.VERTICAL);themeRow.setPadding(dp(12),dp(10),dp(12),dp(10));
        themeRow.addView(text("Thème de couleurs",14,ink()));
        LinearLayout chips=new LinearLayout(activity);chips.setOrientation(LinearLayout.HORIZONTAL);chips.setPadding(0,dp(8),0,0);
        for(int i=0;i<SettingsUi.THEME_NAMES.length;i++){
            final int index=i;
            TextView chip=text(SettingsUi.THEME_NAMES[i],11,ink());chip.setGravity(Gravity.CENTER);chip.setSingleLine(true);
            chip.setClickable(true);chip.setFocusable(true);chip.setContentDescription("Thème "+SettingsUi.THEME_NAMES[i]);
            GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(SettingsUi.themeAccent(i));dot.setSize(dp(16),dp(16));
            chip.setCompoundDrawablesWithIntrinsicBounds(null,dot,null,null);chip.setCompoundDrawablePadding(dp(5));chip.setPadding(dp(2),dp(8),dp(2),dp(6));
            chip.setOnClickListener(v->{if(Control.uiTheme()==index)return;Control.setUiTheme(index);host.applyTheme();host.refresh();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);lp.leftMargin=i==0?0:dp(3);lp.rightMargin=i==SettingsUi.THEME_NAMES.length-1?0:dp(3);
            chips.addView(chip,lp);themeChips.add(chip);
        }
        themeRow.addView(chips);look.addView(themeRow);styleThemeChips();
        uiScale=button("Taille des overlays : "+Control.uiScalePercent()+" %");look.addView(uiScale);
        uiScale.setOnClickListener(v->{final int[] values={100,75,50,25};String[] labels={"100 %","75 %","50 %","25 %"};int selected=0;for(int i=0;i<values.length;i++)if(values[i]==Control.uiScalePercent())selected=i;
            AlertDialog scaleDialog=new AlertDialog.Builder(dialogContext()).setTitle("Taille des overlays").setSingleChoiceItems(labels,selected,(dialog,which)->{host.releaseJoystick(true);Control.setUiScalePercent(values[which]);host.refresh();dialog.dismiss();}).setNegativeButton("Annuler",null).create();
            DialogStyle.attach(scaleDialog);scaleDialog.show();});
        skipCutscenesSwitch = switchRow(look, "Passer les cinématiques"); skipCutscenesSwitch.setChecked(Control.skipCutscenes());
        skipCutscenesSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.skipCutscenes())return; Control.setSkipCutscenes(checked); });
        cutsceneStatus = caption(look, "");  // shown only when the native module reports an error

        LinearLayout battery = group(page, "Batterie");
        batteryResSwitch = switchRow(battery, "Résolution réduite", "Carte et Pokémon en demi-résolution ; menus et texte restent nets."); batteryResSwitch.setChecked(Control.batteryLowRes());
        batteryResSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryLowRes())Control.setBatteryLowRes(checked); });
        batteryFpsSwitch = switchRow(battery, "Limiter à 20 FPS"); batteryFpsSwitch.setChecked(Control.batteryFps20());
        batteryFpsSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryFps20())Control.setBatteryFps20(checked); });
        batteryQualitySwitch = switchRow(battery, "Graphismes bas", "Sans ombres, brouillard, reflets, herbe, HDR ni anticrénelage."); batteryQualitySwitch.setChecked(Control.batteryLowQuality());
        batteryQualitySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryLowQuality())Control.setBatteryLowQuality(checked); });

        LinearLayout menu = section(page,"Raccourcis affichés",false);
        caption(menu,"Choisis les raccourcis du menu R. Les catégories de réglages restent toujours affichées.");
        for(int i=0;i<QuickMenu.CUSTOMIZABLE.length;i++){
            if(QuickMenu.CUSTOMIZABLE[i]==null)continue; // retired entry keeps its bit
            final int bit=1<<i;
            Switch box=switchRow(menu,QuickMenu.CUSTOMIZABLE[i]);box.setChecked(Control.quickMenuVisible(bit));quickMenuChoices[i]=box;
            box.setOnCheckedChangeListener((v,checked)->{if(checked==Control.quickMenuVisible(bit))return;Control.setQuickMenuVisible(bit,checked);host.refresh();});
        }
    }

    private void buildAccountsPage() {
        LinearLayout page = page(MenuIcon.ACCOUNTS, "Données", "Données & diagnostic");
        LinearLayout data = group(page, "Sauvegarde");
        launch(data, "Exporter / importer les réglages", () -> {
            if(PgAssets.rooted())new SettingsBackup(activity).show();
            else activity.startActivity(new Intent(activity,LogActivity.class).putExtra("settingsBackup",true));
        });
        launch(data, "Journal & export", () -> LogActivity.open(activity));

        // ----- Diagnostics (status plumbing kept out of everyday settings) -----
        LinearLayout diag = section(page, "Diagnostic", false);
        state = caption(diag, Control.connection);
        for(TextView status:new TextView[]{steeringStatus,spawnStatus,quickSummaryStatus,transferStatus,quickSpinStatus,buddyStatus,giftStatus,itemStatus,rocketStatus,battleStatus,quickBallStatus}){
            ((ViewGroup)status.getParent()).removeView(status);diag.addView(status);
        }
        cameraRoot = new LinearLayout(activity); cameraRoot.setOrientation(LinearLayout.VERTICAL); diag.addView(cameraRoot);
        cameraStatus = caption(cameraRoot, ""); cameraStatus.setMaxLines(3); cameraStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cameraSample = button("Échantillonner la caméra (10 s)"); cameraRoot.addView(cameraSample);
        cameraSample.setOnClickListener(v -> { if (Control.requestCameraSample()) { host.releaseJoystick(false); host.refresh(); } });
        interactionRoot = new LinearLayout(activity); interactionRoot.setOrientation(LinearLayout.VERTICAL); diag.addView(interactionRoot);
        interactionStatus = caption(interactionRoot, ""); interactionStatus.setMaxLines(3); interactionStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        interactionCapture = button("Capturer capture/spin (60 s)"); interactionRoot.addView(interactionCapture);
        interactionCapture.setOnClickListener(v -> {
            if (Control.interactionDiagnosticRunning()) Control.requestInteractionStop();
            else if (Control.requestInteractionTrace()) host.releaseJoystick(false);
            host.refresh();
        });
    }

    /** Tile summary: how many listed features are on ("2 actives"), or "Désactivé". Arguments are the on/off states. */
    private static String count(boolean... on){
        int n=0;for(boolean b:on)if(b)n++;
        return n==0?"Désactivé":n==1?"1 active":n+" actives";
    }
    private void summary(int index,String value){TextView view=pageSummaries.get(index);if(!value.contentEquals(view.getText()))view.setText(value);}
    private void refreshHome(){
        if(current>=0)return;
        summary(0,Control.desiredEnabled()?(Control.engine.walking?"En marche":"Spoof actif"):"Position réelle");
        summary(1,count(Control.quickSummaryEnabled(),Control.autoThrow(),Control.autoBall(),Control.throwCurve(),Control.throwAlwaysHit(),Control.encounterBerryEnabled()));
        summary(2,count(Control.autoTransferEnabled(),Control.autoTransferEnabled()&&Control.evolveTransferEnabled(),Control.ivCatchMode()!=0||Control.ivOwnedMode()!=0||Control.storageIvMode()!=0));
        summary(3,count(Control.vgpOverlay(),Control.quickSpinEnabled(),Control.itemTransferEnabled(),Control.routeGiftExchange()));
        summary(4,count(Control.smartBattle(),Control.healEnabled(),Control.battleOptions()!=0||Control.rocketOptions()!=0,Control.gymRepeatEnabled()));
        summary(5,count(BuddyEmotionSettings.enabled(),Control.autoIncubate(),(Control.giftOptions()&1)!=0,(Control.giftOptions()&2)!=0));
        summary(6,AutoRaid.running()?"Auto-Raid en cours":ShundoSniper.active()?"Shundo en cours":ShinyScanner.enabled()?"Scan shiny en cours":"Aucune en cours");
        summary(7,"Overlays "+Control.uiScalePercent()+" %");
        summary(8,"Export, journal");
    }

    /** The root panel view; Pad owns attachment to the window and insets wiring. */
    View view(){return panel;}
    boolean isVisible(){return panel.getVisibility()==View.VISIBLE;}
    /** Bring the full-screen panel up; Pad applies current insets immediately after. */
    void show(){showPage(0);}
    /** Opens the sheet on one category, over the game. */
    void showPage(int index){
        open(index);panel.setVisibility(View.VISIBLE);panel.bringToFront();panel.requestApplyInsets();syncSystemBars();
    }
    int pageCount(){return pages.size();}
    String[] pageNames(){return pageShorts.toArray(new String[0]);}
    int[] pageIcons(){int[] icons=new int[pageIcons.size()];for(int i=0;i<icons.length;i++)icons[i]=pageIcons.get(i);return icons;}
    /** A dimmed, theme-tinted backdrop: the game stays faintly visible around the sheet. */
    private static int scrim(){return (SettingsUi.bg()&0x00ffffff)|0xcc000000;}
    /**
     * Applies the bar glyphs now and once more after layout. Unity re-applies its own window
     * appearance while it is starting up, so a single call during show() can be undone before the
     * frame is drawn; the posted repeat lands after that.
     */
    private void syncSystemBars(){
        SettingsUi.systemBars(activity,!SettingsUi.dark());
        panel.postDelayed(()->{if(panel.getVisibility()==View.VISIBLE)SettingsUi.systemBars(activity,!SettingsUi.dark());},250);
    }
    /** Hidden state matches the original startup and the close affix: returns to the game underneath. */
    void hide(){panel.setVisibility(View.GONE);SettingsUi.systemBars(activity,false);}
    /** Release this panel's dialog polling when its owning activity panel is detached. */
    void close(){
        if(berryPicker!=null)berryPicker.close();
        for(AlertDialog dialog:new java.util.ArrayList<>(itemDialogs))dialog.dismiss();
        itemDialogs.clear();handler.removeCallbacksAndMessages(null);
    }

    /** Pads the full-screen settings panel inside the status bar, navigation bar, and IME. */
    void applyInsets(WindowInsets insets) {
        if(insets==null)return;
        int top,bottom,left,right;
        if(Build.VERSION.SDK_INT>=30){
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            android.graphics.Insets safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            top=Math.max(bars.top,safe.top);bottom=Math.max(bars.bottom,safe.bottom);left=Math.max(bars.left,safe.left);right=Math.max(bars.right,safe.right);
        }else{
            top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();
            left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();
        }
        if(top==0){int id=activity.getResources().getIdentifier("status_bar_height","dimen","android");top=id!=0?activity.getResources().getDimensionPixelSize(id):dp(28);}
        panel.setPadding(dp(12)+left,dp(8)+top,dp(12)+right,dp(12)+bottom);
        updateContentWidth();
    }

    void applyTheme(){
        panel.setBackgroundColor(scrim());content.setBackground(SettingsUi.cardBackground(activity));styleTree(panel);
        if(panel.getVisibility()==View.VISIBLE)syncSystemBars();
        for(TextView head:groupHeads){head.setTextColor(SettingsUi.faint());head.setTypeface(SettingsUi.medium());}
        for(TextView c:captions)c.setTextColor(muted());
        if(coords!=null)coords.setTextColor(muted());
        if(speedReadout!=null)speedReadout.setTextColor(accent());
        if(homeStatus!=null)homeStatus.setTextColor(ink());
        if(title!=null)title.setTextColor(ink());
        if(versionLabel!=null)versionLabel.setTextColor(SettingsUi.faint());
        if(discord!=null)discord.setTextColor(accent());
        for(LinearLayout row:homeRows)row.setBackground(SettingsUi.ripple(SettingsUi.cardBackground(activity)));
        for(RowList rows:rowLists)rows.setDividerDrawable(SettingsUi.hairline(activity));
        for(TextView name:homeNames){name.setTextColor(ink());name.setTypeface(SettingsUi.medium());}
        for(TextView summary:pageSummaries)summary.setTextColor(muted());
        if(homeDot!=null)homeDot.setColor(telemetryColor());
        for(MenuIcon icon:homeIcons){icon.invalidate();if(icon.getParent() instanceof View)((View)icon.getParent()).setBackground(SettingsUi.rounded(activity,SettingsUi.accentSoft(),12));}
        styleThemeChips();
        if(backIcon!=null)backIcon.invalidate();
        if(closeIcon!=null)closeIcon.invalidate();
    }

    /** The selected theme gets an accent outline on a soft accent fill; the others stay quiet. */
    private void styleThemeChips(){
        for(int i=0;i<themeChips.size();i++){
            boolean on=i==Control.uiTheme();TextView chip=themeChips.get(i);
            chip.setBackground(SettingsUi.ripple(SettingsUi.shape(activity,on?SettingsUi.accentSoft():SettingsUi.raised(),10,on?SettingsUi.accent():SettingsUi.border())));
            chip.setTextColor(on?ink():muted());chip.setTypeface(on?SettingsUi.medium():Typeface.DEFAULT);
        }
    }

    private static String distance(double meters){
        return meters>=1000?String.format(Locale.FRANCE,"%.1f km",meters/1000):String.format(Locale.FRANCE,"%.0f m",meters);
    }

    private static int telemetryColor(){
        if(!Control.desiredEnabled())return SettingsUi.faint();
        return Control.engine.enabled?SettingsUi.success():SettingsUi.warn();
    }

    /** Recommended item sorting: {item id, maximum kept}. Poké Ball, Super Ball, Hyper Ball, Potion, Super Potion,
     *  Hyper Potion, Potion Max, Rappel, Baie Framby, Baie Nanab, Baie Nanana. */
    private static final int[][] RECOMMENDED_ITEMS={{1,0},{2,50},{3,400},{101,0},{102,0},{103,0},{104,100},{201,0},{701,50},{703,50},{705,50}};

    private void showItemSettings(){
        Control.requestItemCatalog();
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(8),dp(16),dp(8));
        CheckBox enabled=new CheckBox(activity);enabled.setText("Activer le tri automatique des objets");enabled.setChecked(Control.itemTransferEnabled());form.addView(enabled);
        TextView explanation=new TextView(activity);explanation.setText("Coche les objets à trier et règle le maximum à garder. L'enregistrement s'applique à ton sac actuel et aux futurs objets. Une limite de 0 supprime tout cet objet. Les objets non cochés sont gardés.");form.addView(explanation);
        java.util.Map<Integer,Integer> limits=new java.util.HashMap<>();java.util.Set<Integer> selected=new java.util.HashSet<>();
        for(String row:Control.itemLimits().split(";"))try{String[] b=row.split(":");limits.put(Integer.parseInt(b[0]),Integer.parseInt(b[1]));}catch(RuntimeException ignored){}
        for(String row:Control.itemRules().split(";"))try{String[] b=row.split(":");int id=Integer.parseInt(b[0]);selected.add(id);limits.put(id,Integer.parseInt(b[1]));}catch(RuntimeException ignored){}
        LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(activity);scroll.addView(rows);form.addView(scroll,new LinearLayout.LayoutParams(-1,dp(380)));
        java.util.Map<Integer,CheckBox> boxes=new java.util.LinkedHashMap<>();java.util.Map<Integer,EditText> inputs=new java.util.LinkedHashMap<>();
        Runnable populate=()->{for(String row:Control.itemCatalog().split("\n")){String[] b=row.split("\\|",-1);if(b.length!=3)continue;int id;try{id=Integer.parseInt(b[0]);}catch(NumberFormatException invalid){continue;}if(boxes.containsKey(id)){boxes.get(id).setText(NativeText.fr(b[1])+" ("+b[2]+" dans le sac)");continue;}
            LinearLayout line=new LinearLayout(activity);line.setOrientation(LinearLayout.HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox box=new CheckBox(activity);box.setText(NativeText.fr(b[1])+" ("+b[2]+" dans le sac)");box.setChecked(selected.contains(id));line.addView(box,new LinearLayout.LayoutParams(0,-2,1));
            EditText cap=new EditText(dialogContext());cap.setInputType(InputType.TYPE_CLASS_NUMBER);cap.setSingleLine(true);cap.setHint("Max");cap.setText(String.valueOf(limits.containsKey(id)?limits.get(id):100));cap.setContentDescription("Maximum de "+NativeText.fr(b[1])+" à garder");SettingsUi.enabled(cap,enabled.isChecked()&&box.isChecked());box.setOnCheckedChangeListener((button,checked)->SettingsUi.enabled(cap,enabled.isChecked()&&checked));line.addView(cap,new LinearLayout.LayoutParams(dp(80),-2));rows.addView(line);styleTree(line);boxes.put(id,box);inputs.put(id,cap);
        }};populate.run();
        Runnable dependencies=()->{for(java.util.Map.Entry<Integer,CheckBox> entry:boxes.entrySet()){SettingsUi.enabled(entry.getValue(),enabled.isChecked());SettingsUi.enabled(inputs.get(entry.getKey()),enabled.isChecked()&&entry.getValue().isChecked());}};
        enabled.setOnCheckedChangeListener((v,on)->dependencies.run());dependencies.run();
        TextView status=new TextView(activity);status.setText(Control.itemStatus());form.addView(status);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Trier automatiquement l'inventaire").setView(form).setNegativeButton("Annuler",null).setNeutralButton("Recommandés",null).setPositiveButton("Enregistrer & appliquer",null).create();
        final boolean[] recommended={false};
        final Runnable[] poll=new Runnable[1];poll[0]=()->{if(!dialog.isShowing())return;Control.requestItemCatalog();populate.run();dependencies.run();status.setText(Control.itemStatus());handler.postDelayed(poll[0],1000);};
        itemDialogs.add(dialog);
        dialog.setOnDismissListener(d->{handler.removeCallbacks(poll[0]);itemDialogs.remove(dialog);});DialogStyle.attach(dialog);dialog.show();styleTree(form);handler.post(poll[0]);
        // "Recommandés": only the listed items are sorted, with these maximums; every other item is kept.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            recommended[0]=true;enabled.setChecked(true);selected.clear();
            for(int[] item:RECOMMENDED_ITEMS){selected.add(item[0]);limits.put(item[0],item[1]);}
            for(java.util.Map.Entry<Integer,CheckBox> entry:boxes.entrySet()){int id=entry.getKey();entry.getValue().setChecked(selected.contains(id));
                if(selected.contains(id))inputs.get(id).setText(String.valueOf(limits.get(id)));}
            dependencies.run();
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{StringBuilder rules=new StringBuilder(),all=new StringBuilder();for(java.util.Map.Entry<Integer,EditText> e:inputs.entrySet()){int cap;try{cap=Integer.parseInt(e.getValue().getText().toString().trim());if(cap<0||cap>100000)throw new NumberFormatException();}catch(NumberFormatException invalid){e.getValue().setError("Entre 0 et 100000");return;}String rule=e.getKey()+":"+cap+";";all.append(rule);if(boxes.get(e.getKey()).isChecked())rules.append(rule);}
            // Recommended items not in the bag yet keep their rule, so they are sorted once they arrive.
            if(recommended[0])for(int[] item:RECOMMENDED_ITEMS)if(!inputs.containsKey(item[0])){String rule=item[0]+":"+item[1]+";";all.append(rule);rules.append(rule);}
            Control.saveItemRules(enabled.isChecked(),rules.toString(),all.toString());dialog.dismiss();host.refresh();});
    }

    void refresh() {
        refreshHome();
        refreshLocationStatus();
        refreshDiagnostics();
        refreshSummaryVisibility();
        refreshTransfer();
        refreshVgpStatus();
        refreshIncubation();
        refreshVgpOptions();
        refreshBuddy();
        refreshGifts();
        refreshBattles();
        refreshRocket();
        refreshItems();
        refreshQuickCatch();
        refreshLocationOptions();
        refreshThrows();
        skipCutscenesSwitch.setChecked(Control.skipCutscenes());
        routeGiftSwitch.setChecked(Control.routeGiftExchange());
        batteryResSwitch.setChecked(Control.batteryLowRes());batteryFpsSwitch.setChecked(Control.batteryFps20());batteryQualitySwitch.setChecked(Control.batteryLowQuality());
        status(cutsceneStatus,Control.cutsceneSkipStatus());
        refreshHealing();
        refreshSmartBattle();
        refreshDependencies();
    }

    private void refreshDependencies(){
        for(CompoundButton box:vgpChoices)SettingsUi.enabled((View)box.getParent(),Control.vgpOverlay());
        SettingsUi.enabled((View)vgpCooldownStop.getParent(),Control.vgpOverlay());smartRefresh.run();
        for(CompoundButton box:transferProtection)SettingsUi.enabled((View)box.getParent(),Control.autoTransferEnabled());
        SettingsUi.enabled(transferKeepList,Control.autoTransferEnabled());
        SettingsUi.enabled((View)transferOverlaySwitch.getParent(),Control.autoTransferEnabled());
        // Evolve before transferring needs the auto-transfer master; its options need the feature on, and
        // the selected-species list only matters in "Selected species only" mode.
        SettingsUi.enabled((View)evolveTransferSwitch.getParent(),Control.autoTransferEnabled());
        boolean evolveOn=Control.autoTransferEnabled()&&Control.evolveTransferEnabled();
        SettingsUi.enabled(evolveTransferStages,evolveOn);
        SettingsUi.enabled(evolveTransferMode,evolveOn);
        SettingsUi.enabled(evolveTransferList,evolveOn&&Control.evolveTransferMode()==1);
        SettingsUi.enabled(incubatorPriority,Control.autoIncubate()&&Control.incubatorAvailable());
        SettingsUi.enabled((View)giftChoices[2].getParent(),(Control.giftOptions()&2)!=0);
        SettingsUi.enabled((View)joystickSwitch.getParent(),Control.desiredEnabled());
        SettingsUi.enabled(mapTap,Control.desiredEnabled()&&Control.quickBallConfigured());
    }

    private void refreshLocationStatus() {
        if(uiScale!=null)set(uiScale,"Taille des overlays : "+Control.uiScalePercent()+" %");
        set(coords,String.format(Locale.US,"%.6f, %.6f",Control.engine.latitude,Control.engine.longitude));
        set(speedReadout,SettingsUi.speedLabel(Control.engine.speedKmh));
        MotionEngine.RouteProgress route=Control.engine.routeProgress();
        locationSwitch.setChecked(Control.desiredEnabled());
        String movement=!Control.desiredEnabled()?"Position réelle":Control.engine.enabled ? (Control.engine.walking ? "En marche" : "Spoof actif") : "En attente du jeu";
        if (route.total>1 && route.active) movement=String.format(Locale.FRANCE,"Trajet %d/%d · %s restants",route.visited+1,route.total,distance(route.remainingMeters));
        else if (route.total>1 && route.visited==route.total) movement="Spoof actif · trajet terminé";
        set(homeStatus,movement);
        String encounters=Control.encounterCounter()?EncounterCounter.label():"";
        String cooldown=Control.cooldownOverlay()?"Cooldown "+Cooldown.label().replace("Est. ",""):"";
        status(homeCounts,encounters.isEmpty()?cooldown:cooldown.isEmpty()?encounters:encounters+"  ·  "+cooldown);
        if(homeDot!=null)homeDot.setColor(telemetryColor());
        set(state,Control.connection + "\n" + movement + " · lectures de coordonnées " + Control.latitudeReads + "/" + Control.longitudeReads);
        set(steeringStatus,Control.cameraSteeringConfigured() ? Control.cameraSteeringStatus() : "Le joystick suit la caméra");
    }

    private void refreshDiagnostics() {
        cameraRoot.setVisibility(Control.cameraDiagnosticsConfigured() ? View.VISIBLE : View.GONE);
        status(cameraStatus,Control.cameraDiagnosticStatus());
        set(cameraSample,Control.cameraDiagnosticRunning() ? "Échantillonnage caméra…" : "Échantillonner la caméra (10 s)");
        cameraSample.setEnabled(Control.cameraDiagnosticsReady() && !Control.cameraSamplePending());
        interactionRoot.setVisibility(Control.interactionDiagnosticsConfigured() ? View.VISIBLE : View.GONE);
        status(interactionStatus,Control.interactionDiagnosticStatus());
        set(interactionCapture,Control.interactionDiagnosticRunning() ? "Arrêter la capture" : "Capturer capture/spin (60 s)");
        interactionCapture.setEnabled(Control.interactionDiagnosticRunning() || (Control.interactionDiagnosticsReady() && !Control.interactionTracePending()));
    }

    private void refreshSummaryVisibility() {
        boolean summary = Control.quickSummaryConfigured(), ball = Control.quickBallConfigured();
        for(View root:summaryGated)root.setVisibility(summary ? View.VISIBLE : View.GONE);
        for(View root:ballGated)root.setVisibility(ball ? View.VISIBLE : View.GONE);
        for(TextView note:summaryNotes)note.setVisibility(summary ? View.GONE : View.VISIBLE);
        for(TextView note:ballNotes)note.setVisibility(summary && ball ? View.GONE : View.VISIBLE);
    }

    private void refreshTransfer() {
        status(quickSummaryStatus,Control.quickSummaryStatus());
        autoTransferSwitch.setChecked(Control.autoTransferEnabled());
        for(int i=0;i<transferProtection.length;i++)transferProtection[i].setChecked((Control.transferProtect()&(1<<i))!=0);
        for(int i=0;i<3;i++){pvpKeep[i].setChecked(Control.pvpKeep(i));set(pvpRank[i],"Top "+Control.pvpRank(i));pvpRank[i].setEnabled(Control.pvpKeep(i));}
        if(transferKeepList!=null){String kept=Control.transferKeepSpecies();int n=kept.isEmpty()?0:kept.split(",").length;set(transferKeepList,n>0?"Ma liste d'espèces : "+n:"Ma liste d'espèces");}
        if(transferOverlaySwitch!=null){transferOverlaySwitch.setChecked(Control.transferOverlayEnabled());transferOverlaySwitch.setEnabled(Control.autoTransferEnabled());transferOverlaySwitch.setAlpha(1f);}
        if(evolveTransferSwitch!=null)evolveTransferSwitch.setChecked(Control.evolveTransferEnabled());
        set(evolveTransferStages,"Évolution : "+(Control.evolveTransferStages()==2?"toutes les étapes":"une fois"));
        set(evolveTransferMode,"Pour : "+(Control.evolveTransferMode()==1?"espèces choisies":"règles de transfert"));
        if(evolveTransferList!=null){String sel=Control.evolveTransferSpecies();int n=sel.isEmpty()?0:sel.split(",").length;set(evolveTransferList,n>0?"Espèces choisies : "+n:"Espèces choisies");}
        status(transferStatus,Control.transferStatus());
    }

    private void refreshVgpStatus() {
        vgpSwitch.setChecked(Control.vgpOverlay());status(vgpStatus,Control.vgpStatus());
    }

    private void refreshIncubation() {
        incubatorSwitch.setChecked(Control.autoIncubate());
        incubatorSwitch.setEnabled(Control.incubatorAvailable());
        set(incubatorPriority,"Œuf prioritaire : " + (Control.incubateLongest() ? "le plus long" : "le plus court"));
        status(incubatorStatus,Control.autoIncubate() ? Control.incubatorStatus() : "");
    }

    private void refreshVgpOptions() {
        for(int i=0;i<3;i++)vgpChoices[i].setChecked((Control.vgpOptions()&(1<<i))!=0);
        vgpCooldownStop.setChecked(Control.vgpStopOnCooldown());
    }

    private void refreshBuddy() {buddyRefresh.run();status(buddyStatus,BuddyEmotionSettings.status());}

    private void refreshGifts() {
        for(int i=0;i<3;i++)giftChoices[i].setChecked((Control.giftOptions()&(1<<i))!=0);status(giftStatus,Control.giftStatus());
    }

    private void refreshBattles() {
        for(int i=0;i<10;i++)battleChoices[i].setChecked((Control.battleOptions()&(1<<i))!=0);status(battleStatus,Control.battleStatus());
        gymRepeatSwitch.setChecked(Control.gymRepeatEnabled());
    }

    private void refreshRocket() {
        for(int i=0;i<3;i++)rocketChoices[i].setChecked((Control.rocketOptions()&(1<<i))!=0);status(rocketStatus,Control.rocketStatus());
    }

    private void refreshItems() {
        set(itemSettings,"Tri auto des objets : "+(Control.itemTransferEnabled()?"activé":"désactivé"));status(itemStatus,Control.itemStatus());
    }

    private void refreshQuickCatch() {
        berrySwitch.setChecked(Control.encounterBerryEnabled());
        set(berryPriority,"Priorité : "+BerryPriorityPicker.summary());
        berryPicker.refresh();
        quickSpinSwitch.setChecked(Control.quickSpinEnabled());status(quickSpinStatus,Control.quickSpinStatus());
        set(quickSummaryLabel,Control.quickSummaryAutoDismiss() ? "Capture rapide" : "Résumé de capture accéléré");
        quickSummarySwitch.setChecked(Control.quickSummaryEnabled());
        quickSummarySwitch.setEnabled(Control.quickSummaryConfigured());
        status(quickBallStatus,Control.quickBallStatus());
    }

    private void refreshLocationOptions() {
        for(int i=0;i<quickMenuChoices.length;i++)if(quickMenuChoices[i]!=null)quickMenuChoices[i].setChecked(Control.quickMenuVisible(1<<i));
        idleWalkSwitch.setChecked(Control.idleWalking());idleWalkSwitch.setEnabled(Control.desiredEnabled());idleWalkSwitch.setAlpha(Control.desiredEnabled()?1f:.45f);
        set(mapTap,"Appui sur la carte : "+new String[]{"rien","marcher","téléporter","marcher (double : téléporter)"}[Control.mapTapMode()]);
        mapTap.setEnabled(Control.quickBallConfigured());
        spawnSwitch.setChecked(Control.spawnBooster());spawnSwitch.setEnabled(Control.spawnAllowed());status(spawnStatus,Control.spawnStatus());
        localTimeSwitch.setChecked(Control.localTimeOverlay());
        encounterCounterSwitch.setChecked(Control.encounterCounter());encounterOverlaySwitch.setChecked(Control.encounterOverlay());
        SettingsUi.enabled(encounterOverlaySwitch,Control.encounterCounter());
    }

    // Indexed by IV mode: 0 off, 1 level and attack/defense/stamina, 2 level and percent, 3 all three.
    private static final String[] IV_MODES={"désactivé","Niv · 15/14/12","Niv · IV","Niv · IV · 15/14/12","Niv · IV · 15/14/12 · PvP"};

    private void refreshThrows() {
        set(throwGrade,"Qualité du lancer : "+new String[]{"désactivée","Bien","Super","Excellent"}[Control.throwRating()]);
        throwGrade.setEnabled(Control.quickBallConfigured()&&Control.throwVariety()==0);
        set(throwVariety,"Lancers variés : "+Control.THROW_VARIETY_LABELS[Control.throwVariety()]);
        throwVariety.setEnabled(Control.quickBallConfigured());
        curveSwitch.setChecked(Control.throwCurve());curveSwitch.setEnabled(Control.quickBallConfigured());
        alwaysHitSwitch.setChecked(Control.throwAlwaysHit());alwaysHitSwitch.setEnabled(Control.throwAlwaysHitReady() || Control.throwAlwaysHit());
        arSwitch.setChecked(Control.throwAr());arSwitch.setEnabled(Control.throwArReady());
        catchChanceSwitch.setChecked(Control.catchChanceShown());autoBallSwitch.setChecked(Control.autoBall());
        set(autoBallTarget,"Objectif : "+Control.autoBallTarget()+" %");autoBallTarget.setEnabled(Control.autoBall());
        autoThrowSwitch.setChecked(Control.autoThrow());
        autoThrowMapSwitch.setChecked(Control.autoThrowOnMap());
        autoThrowMapSwitch.setEnabled(Control.autoThrow());
        set(ivCatchMode,"Rencontre : "+IV_MODES[Control.ivCatchMode()]);ivCatchMode.setEnabled(Control.quickBallConfigured());
        set(ivOwnedMode,"Fiche du Pokémon : "+IV_MODES[Control.ivOwnedMode()]);ivOwnedMode.setEnabled(Control.quickBallConfigured());
        set(storageIv,"Stockage : "+IV_MODES[Control.storageIvMode()]);
        String ivText=Control.ivDisplayStatus(), storageText=Control.storageIvStatus();
        status(ivStatus,ivText.isEmpty()?storageText:storageText.isEmpty()?ivText:ivText+"\n"+storageText);
    }
}
