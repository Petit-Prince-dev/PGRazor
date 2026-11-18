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
    private final LinearLayout panel, content, body, home, results;
    private final ScrollView bodyScroll;
    private TextView coords, speedReadout, homeStatus;
    private View closeIcon, themeIcon, backIcon;
    private TextView title, versionLabel, discord;
    private SettingsUi.Dot homeDot;
    private Switch darkSwitch;
    // Settings search: the index is built once on first use, after every page has painted its labels.
    private SettingsSearch searchIndex;
    private final java.util.List<View> searchTargets=new java.util.ArrayList<>();
    private final java.util.HashSet<String> searchSeen=new java.util.HashSet<>();
    private LinearLayout searchBar;
    private EditText searchField;
    private View clearSearch;
    private String searchQuery="";
    private boolean suppressSearch;
    private int lastContentWidth=-1;
    // Category navigation: one home row and one page per category; current is -1 on the home screen.
    private final java.util.List<LinearLayout> pages=new java.util.ArrayList<>(), homeRows=new java.util.ArrayList<>();
    private final java.util.List<RowList> rowLists=new java.util.ArrayList<>();
    private final java.util.List<String> pageTitles=new java.util.ArrayList<>();
    private final java.util.List<TextView> pageSummaries=new java.util.ArrayList<>(), homeNames=new java.util.ArrayList<>();
    private final java.util.List<MenuIcon> homeIcons=new java.util.ArrayList<>();
    private LinearLayout homeTarget;
    private int current=-1, homeScroll;
    private Switch incubatorSwitch;
    private Button incubatorPriority;
    private Button uiScale;
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
    private final CheckBox[] vgpChoices=new CheckBox[3];
    private CheckBox vgpCooldownStop;
    // Status / caption lines (muted, readiness driven).
    private TextView state, steeringStatus, spawnStatus, quickSummaryStatus, transferStatus, quickSpinStatus,
        buddyStatus, giftStatus, itemStatus, rocketStatus, battleStatus, quickBallStatus, ivStatus, cameraStatus, interactionStatus;
    // Multi-option groups keep checkboxes / radios.
    private final CheckBox[] transferProtection=new CheckBox[8];
    private final Switch[] pvpKeep=new Switch[3];
    private final Button[] pvpRank=new Button[3];
    private final CheckBox[] giftChoices=new CheckBox[3];
    private final CheckBox[] quickMenuChoices=new CheckBox[QuickMenu.CUSTOMIZABLE.length];
    private final CheckBox[] rocketChoices=new CheckBox[3];
    private final CheckBox[] battleChoices=new CheckBox[10];
    private Runnable buddyRefresh=()->{},smartRefresh=()->{};
    // PGRazor Root updates (rooted builds only): status line read from the root helper, throttled.
    private TextView rootUpdateStatus;private Switch rootUpdateAuto;private long rootUpdateRead;
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

    /** One titled card of rows: a quiet label above a surface with a hairline border. */
    private LinearLayout group(LinearLayout owner,String label){
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        TextView head=text(label,13,muted());head.setTypeface(SettingsUi.medium());
        LinearLayout.LayoutParams headParams=new LinearLayout.LayoutParams(-1,-2);headParams.topMargin=dp(20);headParams.bottomMargin=dp(8);headParams.leftMargin=dp(2);
        root.addView(head,headParams);
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(SettingsUi.cardBackground(activity));card.setPadding(dp(4),dp(2),dp(4),dp(2));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);
        RowList rows=new RowList();rows.setTag(SettingsUi.TAG_GROUP,label);card.addView(rows);
        root.addView(card);owner.addView(root);groupHeads.add(head);
        return rows;
    }
    private LinearLayout plain(LinearLayout owner){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);owner.addView(v);return v;}
    private Button launch(LinearLayout owner,String label,Runnable action){Button b=button(label);owner.addView(b);b.setOnClickListener(v->action.run());return b;}

    /** A collapsible card. Its header is the whole hit target, so opening it never needs a second tap target. */
    private LinearLayout section(LinearLayout owner,String label,boolean open){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(SettingsUi.cardBackground(activity));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);
        LinearLayout head=new LinearLayout(activity);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);head.setMinimumHeight(dp(52));head.setPadding(dp(14),0,dp(10),0);
        TextView title=text(label,14,ink());head.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
        SettingsUi.Chevron chev=new SettingsUi.Chevron(activity);chev.open=open;head.addView(chev,new LinearLayout.LayoutParams(dp(24),dp(24)));
        RowList rows=new RowList();rows.setVisibility(open?View.VISIBLE:View.GONE);rows.setPadding(dp(4),0,dp(4),dp(4));
        rows.setTag(SettingsUi.TAG_SECTION_HEAD,head);
        head.setOnClickListener(v->{boolean o=rows.getVisibility()!=View.VISIBLE;rows.setVisibility(o?View.VISIBLE:View.GONE);chev.open=o;chev.invalidate();});
        card.addView(head);card.addView(rows);
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.topMargin=dp(20);
        owner.addView(card,cardParams);
        return rows;
    }

    private Switch switchRow(LinearLayout owner,String label){
        LinearLayout r=new LinearLayout(activity);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setMinimumHeight(dp(52));r.setPadding(dp(10),dp(2),dp(6),dp(2));
        TextView t=text(label,14,ink());t.setPadding(0,0,dp(8),0);r.addView(t,new LinearLayout.LayoutParams(0,-2,1f));
        Switch sw=new Switch(activity);sw.setContentDescription(label);sw.setClickable(true);r.addView(sw,new LinearLayout.LayoutParams(-2,-2));
        owner.addView(r);return sw;
    }
    private TextView caption(LinearLayout owner,String s){
        TextView t=text(s,12,muted());t.setLineSpacing(0,1.25f);t.setPadding(dp(8),dp(6),dp(8),dp(10));
        owner.addView(t);captions.add(t);return t;
    }

    /** Shows the destination theme: golden sun in dark mode, indigo moon in light mode. */
    private final class ThemeIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ThemeIcon(Context c){super(c);setFocusable(true);setClickable(true);setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3345e1ba),null,rounded(0xffffffff,24)));}
        protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f;
            // One neutral glyph: the action changes the theme, it is not a second status colour.
            paint.setColor(muted());paint.setStyle(Paint.Style.FILL);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
            if(Control.darkTheme()){
                c.drawCircle(x,y,dp(5),paint);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(x+(float)Math.cos(a)*dp(8),y+(float)Math.sin(a)*dp(8),x+(float)Math.cos(a)*dp(11),y+(float)Math.sin(a)*dp(11),paint);}
            }else{
                Path moon=new Path();moon.addCircle(x,y,dp(10),Path.Direction.CW);
                Path cut=new Path();cut.addCircle(x+dp(6),y-dp(4),dp(9),Path.Direction.CW);moon.op(cut,Path.Op.DIFFERENCE);c.drawPath(moon,paint);
            }
        }
    }
    /** A clean close affix: a small X drawn inside a 48dp hitbox. */
    private final class CloseIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CloseIcon(Context c){super(c);setContentDescription("Fermer les paramètres PGRazor");setFocusable(true);setClickable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3345e1ba),null,rounded(0xffffffff,24)));}
        protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=dp(8);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(2));paint.setColor(muted());c.drawLine(cx-r,cy-r,cx+r,cy+r,paint);c.drawLine(cx-r,cy+r,cx+r,cy-r,paint);}
    }

    /** A drawn back chevron inside a 48dp hitbox; shown only while a category page is open. */
    private final class BackIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BackIcon(Context c){super(c);setContentDescription("Retour à tous les paramètres");setFocusable(true);setClickable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3345e1ba),null,rounded(0xffffffff,24)));}
        protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=dp(7);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(dp(2));paint.setColor(accent());
            Path p=new Path();p.moveTo(cx+r/2,cy-r);p.lineTo(cx-r/2,cy);p.lineTo(cx+r/2,cy+r);c.drawPath(p,paint);}
    }

    /**
     * Adds a category: an icon tile row on the home screen and the page it opens. Content is built
     * directly into the returned page container.
     */
    private LinearLayout page(int icon,String label){
        final int index=pages.size();
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(64));
        row.setPadding(dp(12),dp(10),dp(10),dp(10));row.setClickable(true);row.setFocusable(true);
        row.setBackground(SettingsUi.ripple(SettingsUi.cardBackground(activity)));
        LinearLayout tile=new LinearLayout(activity);tile.setGravity(Gravity.CENTER);tile.setBackground(SettingsUi.rounded(activity,SettingsUi.accentSoft(),10));
        MenuIcon glyph=new MenuIcon(activity,icon);glyph.accent=true;tile.addView(glyph,new LinearLayout.LayoutParams(dp(20),dp(20)));
        row.addView(tile,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout texts=new LinearLayout(activity);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(dp(12),0,0,0);
        TextView name=text(label,14,ink());name.setTypeface(SettingsUi.medium());texts.addView(name);
        TextView summary=text("",12,muted());summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);texts.addView(summary);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        SettingsUi.Chevron chevron=new SettingsUi.Chevron(activity);row.addView(chevron,new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.bottomMargin=dp(8);(homeTarget==null?home:homeTarget).addView(row,rowParams);
        row.setOnClickListener(v->open(index));
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.setVisibility(View.GONE);content.setPadding(0,0,0,dp(24));body.addView(content);
        pages.add(content);pageTitles.add(label);homeRows.add(row);homeNames.add(name);homeIcons.add(glyph);pageSummaries.add(summary);
        captions.add(summary);
        return content;
    }
    /** Shown at the top of a page while some of its groups are hidden because the game has not reported support. */
    private void unavailableNote(LinearLayout owner,boolean needsBall){
        TextView note=caption(owner,"Certains paramètres ne sont pas encore disponibles.");
        (needsBall?ballNotes:summaryNotes).add(note);
    }
    /** Shows the home screen (index -1) or one category page, keeping the home scroll position for the way back. */
    private void open(int index){
        hideKeyboard();
        if(current<0&&index>=0)homeScroll=bodyScroll.getScrollY();
        clearQuery();
        current=index;refreshHome();
        results.setVisibility(View.GONE);
        home.setVisibility(index<0?View.VISIBLE:View.GONE);
        for(int i=0;i<pages.size();i++)pages.get(i).setVisibility(i==index?View.VISIBLE:View.GONE);
        searchBar.setVisibility(index<0?View.VISIBLE:View.GONE);
        set(title,index<0?EncounterCounter.title():pageTitles.get(index));
        backIcon.setVisibility(index<0?View.GONE:View.VISIBLE);
        // The theme toggle and version make room for the longer page titles; theme is also under Display & Performance.
        themeIcon.setVisibility(index<0?View.VISIBLE:View.GONE);versionLabel.setVisibility(index<0?View.VISIBLE:View.GONE);
        final int y=index<0?homeScroll:0;bodyScroll.post(()->bodyScroll.scrollTo(0,y));
    }

    SettingsPanel(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundColor(SettingsUi.bg());
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        panel.addView(content,new LinearLayout.LayoutParams(-1,-1));
        // Full-screen settings header: back (on a category page), title, theme and close controls.
        LinearLayout header = new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        backIcon = new BackIcon(activity); backIcon.setVisibility(View.GONE); header.addView(backIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        backIcon.setOnClickListener(v -> open(-1));
        title = text("PGRazor", 20, ink()); title.setTypeface(SettingsUi.medium()); title.setPadding(dp(4), 0, 0, 0);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        themeIcon = new ThemeIcon(activity); header.addView(themeIcon,new LinearLayout.LayoutParams(dp(44),dp(48)));
        themeIcon.setOnClickListener(v -> {Control.setDarkTheme(!Control.darkTheme());host.applyTheme();});
        versionLabel = text(BuildVersion.NAME, 11, SettingsUi.faint());
        versionLabel.setTypeface(SettingsUi.mono());
        versionLabel.setSingleLine(true); versionLabel.setGravity(Gravity.CENTER_VERTICAL);
        versionLabel.setPadding(dp(4), 0, dp(6), 0);
        versionLabel.setContentDescription("Version PGRazor " + BuildVersion.NAME);
        captions.add(versionLabel);
        header.addView(versionLabel, new LinearLayout.LayoutParams(-2, dp(48)));
        closeIcon = new CloseIcon(activity); header.addView(closeIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        // Search sits at the top of Settings, above the scrolling body, so it is always one tap away.
        searchBar = new LinearLayout(activity); searchBar.setOrientation(LinearLayout.HORIZONTAL); searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(SettingsUi.fieldBackground(activity)); searchBar.setMinimumHeight(dp(48)); searchBar.setPadding(dp(12),0,dp(2),0);
        SettingsUi.SearchGlyph glass = new SettingsUi.SearchGlyph(activity);
        LinearLayout.LayoutParams glassParams=new LinearLayout.LayoutParams(dp(18),dp(18));glassParams.rightMargin=dp(10);searchBar.addView(glass,glassParams);
        searchField = new EditText(activity);
        searchField.setTag(SettingsUi.TAG_PLAIN_FIELD,Boolean.TRUE);
        searchField.setBackground(null);
        searchField.setTextSize(14); searchField.setTextColor(ink()); searchField.setHintTextColor(SettingsUi.faint());
        searchField.setHint("Rechercher un réglage"); searchField.setSingleLine(true); searchField.setPadding(0,0,0,0);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setContentDescription("Rechercher un réglage par nom, description ou catégorie");
        searchBar.addView(searchField,new LinearLayout.LayoutParams(0,dp(48),1f));
        clearSearch = new SettingsUi.ClearGlyph(activity); clearSearch.setVisibility(View.GONE);
        searchBar.addView(clearSearch,new LinearLayout.LayoutParams(dp(40),dp(48)));
        clearSearch.setOnClickListener(v -> searchField.setText(""));
        searchField.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable s){if(!suppressSearch)applySearch(s.toString());}
        });
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,-2);searchParams.bottomMargin=dp(12);content.addView(searchBar,searchParams);

        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        bodyScroll = new ScrollView(activity); bodyScroll.setFillViewport(false); bodyScroll.setVerticalScrollBarEnabled(false);
        bodyScroll.addView(body, new ScrollView.LayoutParams(-1,-2));
        content.addView(bodyScroll, new LinearLayout.LayoutParams(-1,0,1));

        discord = text("Rejoindre notre Discord", 12, accent());
        discord.setGravity(Gravity.CENTER); discord.setMinimumHeight(dp(44));
        discord.setContentDescription("Rejoindre la communauté Discord de PGRazor");
        discord.setClickable(true); discord.setFocusable(true);
        discord.setBackground(SettingsUi.pressable(activity));
        content.addView(discord, new LinearLayout.LayoutParams(-1,dp(44)));
        discord.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.gg/9QqfkDSQX3"))); }
            catch (android.content.ActivityNotFoundException unavailable) {
                android.widget.Toast.makeText(activity, "Aucune appli pour ouvrir les liens Discord", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        // Home screen: the live telemetry card, then grouped category cards.
        home = new LinearLayout(activity); home.setOrientation(LinearLayout.VERTICAL); body.addView(home);
        buildTelemetryCard();
        homeTarget=homeGroup("Jeu");
        buildLocationPage();
        buildCatchingPage();
        buildPokemonPage();
        buildGoPlusPage();
        buildBattlesPage();
        buildSocialPage();
        homeTarget=homeGroup("Automatisation");
        buildAutomationPage();
        buildEventsPage();
        homeTarget=homeGroup("App");
        buildDisplayPage();
        buildAccountsPage();   // last: its Diagnostics group collects status lines created by the pages above
        homeTarget=null;
        results = new LinearLayout(activity); results.setOrientation(LinearLayout.VERTICAL); results.setVisibility(View.GONE); body.addView(results);

        // Closing the full-screen settings panel simply returns to the game (or the quick menu on the next tap).
        closeIcon.setOnClickListener(v -> { hideKeyboard(); hide(); });
        // The settings panel is a full-screen modal now; it consumes taps so the game behind it stays inert.
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
        lastContentWidth=extra;content.setPadding(extra,0,extra,0);
    }

    private void hideKeyboard(){
        try{
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null&&searchField!=null)keyboard.hideSoftInputFromWindow(searchField.getWindowToken(),0);
            if(searchField!=null)searchField.clearFocus();
        }catch(RuntimeException ignored){}
    }

    private LinearLayout homeGroup(String label){
        TextView head=text(label,12,SettingsUi.faint());head.setTypeface(SettingsUi.medium());
        LinearLayout.LayoutParams headParams=new LinearLayout.LayoutParams(-1,-2);headParams.topMargin=dp(22);headParams.bottomMargin=dp(8);headParams.leftMargin=dp(2);
        home.addView(head,headParams);
        LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);home.addView(list);
        return list;
    }

    private void buildTelemetryCard(){
        LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SettingsUi.cardBackground(activity));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);card.setPadding(dp(14),dp(12),dp(14),dp(12));
        LinearLayout top=new LinearLayout(activity);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
        homeDot=new SettingsUi.Dot(activity,SettingsUi.success());top.addView(homeDot,new LinearLayout.LayoutParams(dp(8),dp(8)));
        homeStatus=text("Démarrage",12,muted());homeStatus.setSingleLine(true);homeStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1f);statusParams.leftMargin=dp(8);top.addView(homeStatus,statusParams);
        speedReadout=text("0.0 km/h",12,accent());speedReadout.setTypeface(SettingsUi.mono());top.addView(speedReadout);
        card.addView(top);
        coords=text(String.format(Locale.US,"%.6f, %.6f",Control.engine.latitude,Control.engine.longitude),13,ink());coords.setTypeface(SettingsUi.mono());coords.setSingleLine(true);
        LinearLayout.LayoutParams coordsParams=new LinearLayout.LayoutParams(-1,-2);coordsParams.topMargin=dp(8);card.addView(coords,coordsParams);
        home.addView(card);
    }

    private void buildLocationPage() {
        LinearLayout loc = page(MenuIcon.TELEPORT, "Position & carte");
        LinearLayout core = group(loc, "Position & déplacement");
        locationSwitch=switchRow(core,"Spoof de position");locationSwitch.setChecked(Control.desiredEnabled());
        locationSwitch.setOnCheckedChangeListener((v,checked)->{if(checked==Control.desiredEnabled())return;host.releaseJoystick(true);Control.setLocationSpoofing(checked);host.refresh();});
        steeringStatus = caption(core, "Le joystick suit la caméra");
        launch(core, "Coordonnées & vitesse", host::editLocation);
        launch(core, "Carte du monde", host::openMap);
        joystickSwitch = switchRow(core, "Afficher le joystick"); joystickSwitch.setChecked(Control.joystickVisible());
        joystickSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.joystickVisible())return; host.setJoystickVisible(checked); });
        idleWalkSwitch = switchRow(core, "Marche à l'arrêt");idleWalkSwitch.setChecked(Control.idleWalking());
        idleWalkSwitch.setOnCheckedChangeListener((v,checked)->{if(checked!=Control.idleWalking())Control.setIdleWalking(checked);});
        caption(core, "À l'arrêt, marche en cercle. Une vitesse de 0 la met en pause.");

        LinearLayout map = group(loc, "Carte du jeu");
        mapTap = button("Appui sur la carte : ne rien faire"); map.addView(mapTap);
        mapTap.setOnClickListener(v -> { Control.setMapTapMode((Control.mapTapMode()+1)%4); host.refresh(); });
        spawnSwitch = switchRow(map, "Booster d'apparitions (80 m)"); spawnSwitch.setChecked(Control.spawnBooster());
        spawnSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.spawnBooster())return; Control.setSpawnBooster(spawnSwitch.isChecked()); });
        spawnStatus = caption(map, "");
        caption(map, "Peut faire ramer dans les zones avec beaucoup de Pokémon. Désactive-le si le jeu saccade.");

        LinearLayout overlays = group(loc, "Cooldown & heure");
        cooldownSwitch = switchRow(overlays, "Overlay du cooldown"); cooldownSwitch.setChecked(Control.cooldownOverlay());
        cooldownSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.cooldownOverlay())return; Control.setCooldownOverlay(cooldownSwitch.isChecked()); host.refresh(); });
        launch(overlays, "Historique du cooldown", () -> Cooldown.show(activity));
        localTimeSwitch = switchRow(overlays, "Afficher l'heure locale"); localTimeSwitch.setChecked(Control.localTimeOverlay());
        localTimeSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.localTimeOverlay())return; Control.setLocalTimeOverlay(localTimeSwitch.isChecked()); host.refresh(); });
        caption(overlays, "Heure à ta position spoofée.");
        LinearLayout encounters = group(loc, "Compteur de rencontres");
        encounterCounterSwitch = switchRow(encounters, "Compter les rencontres"); encounterCounterSwitch.setChecked(Control.encounterCounter());
        encounterCounterSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterCounter())return; Control.setEncounterCounter(encounterCounterSwitch.isChecked()); host.refresh(); });
        encounterOverlaySwitch = switchRow(encounters, "Afficher le compteur sur la carte"); encounterOverlaySwitch.setChecked(Control.encounterOverlay());
        encounterOverlaySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterOverlay())return; Control.setEncounterOverlay(encounterOverlaySwitch.isChecked()); host.refresh(); });
        caption(encounters, "Pokémon sauvages rencontrés par ce compte ces dernières 24 h sur cet appareil avec PGRazor actif, affichés à côté du titre PGRazor. Les rencontres faites ailleurs, et celles de Leurre, d'Encens, de raid et d'étude, ne sont pas comptées.");
    }

    private void buildCatchingPage() {
        LinearLayout page = page(MenuIcon.CATCH, "Capture");
        unavailableNote(page, true);

        // ----- Quick catch (gated by catch-summary support) -----
        LinearLayout quick = group(page, "Capture rapide"); summaryGated.add((View)quick.getParent());
        LinearLayout sumRow = new LinearLayout(activity); sumRow.setOrientation(LinearLayout.HORIZONTAL); sumRow.setGravity(Gravity.CENTER_VERTICAL); sumRow.setMinimumHeight(dp(44));
        quickSummaryLabel = new TextView(activity); quickSummaryLabel.setTextSize(14); sumRow.addView(quickSummaryLabel, new LinearLayout.LayoutParams(0,-2,1f));
        quickSummarySwitch = new Switch(activity); quickSummarySwitch.setContentDescription("Capture rapide"); sumRow.addView(quickSummarySwitch, new LinearLayout.LayoutParams(-2,-2)); quick.addView(sumRow);
        quickSummarySwitch.setChecked(Control.quickSummaryEnabled());
        quickSummarySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.quickSummaryEnabled())return; Control.setQuickSummaryEnabled(quickSummarySwitch.isChecked()); host.refresh(); });
        quickSummaryStatus = caption(quick, "");

        // ----- Throws, ball choice and auto throw (gated by ball-animation support) -----
        LinearLayout throwC = group(page, "Lancers"); ballGated.add((View)throwC.getParent());
        throwGrade = button("Qualité du lancer : OFF"); throwC.addView(throwGrade);
        throwGrade.setOnClickListener(v -> { Control.setThrowGrade((Control.throwRating()+1)%4); host.refresh(); });
        throwVariety = button("Lancers variés : Off"); throwC.addView(throwVariety);
        throwVariety.setOnClickListener(v -> { Control.setThrowVariety((Control.throwVariety()+1)%4); host.refresh(); });
        caption(throwC, "Lancers variés choisit une qualité au hasard à chaque lancer, à chances égales. « Tous » inclut un lancer simple. Remplace la qualité du lancer quand c'est activé.");
        curveSwitch = switchRow(throwC, "Toujours en balle courbe"); curveSwitch.setChecked(Control.throwCurve());
        curveSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwCurve())return; Control.setThrowCurve(curveSwitch.isChecked()); host.refresh(); });
        alwaysHitSwitch = switchRow(throwC, "Toujours toucher"); alwaysHitSwitch.setChecked(Control.throwAlwaysHit());
        alwaysHitSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwAlwaysHit())return; Control.setThrowAlwaysHit(alwaysHitSwitch.isChecked()); host.refresh(); });
        arSwitch = switchRow(throwC, "Bonus de lancer RA+"); arSwitch.setChecked(Control.throwAr());
        arSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.throwAr())return; Control.setThrowAr(arSwitch.isChecked()); host.refresh(); });
        caption(throwC, "Rencontres sauvages. Se combine avec la qualité du lancer et la balle courbe.");

        LinearLayout ballC = group(page, "Chance de capture & choix de Ball"); ballGated.add((View)ballC.getParent());
        catchChanceSwitch = switchRow(ballC, "Afficher la chance de capture"); catchChanceSwitch.setChecked(Control.catchChanceShown());
        catchChanceSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.catchChanceShown())return; Control.setCatchChanceShown(checked); host.refresh(); });
        autoBallSwitch = switchRow(ballC, "Ball auto"); autoBallSwitch.setChecked(Control.autoBall());
        autoBallSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoBall())return; Control.setAutoBall(checked); host.refresh(); });
        autoBallTarget = button("Objectif Ball auto : "+Control.autoBallTarget()+"%"); ballC.addView(autoBallTarget);
        autoBallTarget.setOnClickListener(v -> {
            int[] targets=Control.AUTO_BALL_TARGETS;int next=targets[0];
            for(int i=0;i<targets.length;i++)if(targets[i]==Control.autoBallTarget()){next=targets[(i+1)%targets.length];break;}
            Control.setAutoBallTarget(next); host.refresh(); });
        caption(ballC, "Rencontres manuelles. La chance utilise le taux de capture du serveur pour chaque Ball avec ta baie, ta qualité de lancer et la balle courbe ; le taux du serveur inclut déjà ton bonus de médaille de type. Ball auto choisit la Poké Ball, Super Ball ou Hyper Ball la moins chère qui atteint l'objectif, à l'ouverture de la rencontre et après chaque fuite ; une Ball choisie à la main est gardée jusqu'à la fuite suivante. N'utilise jamais de Master Ball.");

        LinearLayout autoC = group(page, "Lancer auto"); ballGated.add((View)autoC.getParent());
        autoThrowSwitch = switchRow(autoC, "Lancer auto"); autoThrowSwitch.setChecked(Control.autoThrow());
        autoThrowSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoThrow())return; Control.setAutoThrow(autoThrowSwitch.isChecked()); host.refresh(); });
        autoThrowMapSwitch = switchRow(autoC, "Capture depuis la carte (touche le Pokémon)");
        autoThrowMapSwitch.setChecked(Control.autoThrowOnMap());
        autoThrowMapSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoThrowOnMap())return; Control.setAutoThrowOnMap(checked); host.refresh(); });
        quickBallStatus = caption(autoC, "");

        LinearLayout berryC = group(page, "Baies"); summaryGated.add((View)berryC.getParent());
        berrySwitch = switchRow(berryC, "Baies de capture & overlay"); berrySwitch.setChecked(Control.encounterBerryEnabled());
        berrySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.encounterBerryEnabled())return; Control.setEncounterBerryEnabled(berrySwitch.isChecked()); host.refresh(); });
        berryPicker = new BerryPriorityPicker(activity, host::refresh);
        berryPriority = button("Priorité des baies : "+BerryPriorityPicker.summary()); berryC.addView(berryPriority);
        berryPriority.setOnClickListener(v -> berryPicker.show());
    }

    private void buildPokemonPage() {
        LinearLayout page = page(MenuIcon.BOX, "Pokémon & transfert");
        unavailableNote(page, false);

        // ----- Transfer on catch (gated by catch-summary support) -----
        LinearLayout transfer = plain(page); summaryGated.add(transfer);
        LinearLayout txC = group(transfer, "Transférer à la capture");
        autoTransferSwitch = switchRow(txC, "Transférer sous 90 % d'IV"); autoTransferSwitch.setChecked(Control.autoTransferEnabled());
        autoTransferSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.autoTransferEnabled())return; Control.setAutoTransferEnabled(autoTransferSwitch.isChecked()); host.refresh(); });
        caption(txC, "Les transferts sont définitifs. Les favoris et les données inconnues sont toujours gardés.");
        transferStatus = caption(txC, "");

        LinearLayout keepC = group(transfer, "Toujours garder");
        // Indexed by protection bit; costume (bit 7) is listed next to event.
        String[] protections={"Garder légendaires / fabuleux / Ultra-Chimères","Garder les Pokémon shiny","Garder les Pokémon d'événement","Garder les Pokémon avec fond spécial","Garder les XXS","Garder les XXL","Garder les 0 % IV (0/0/0)","Garder les Pokémon costumés"};
        for(int i:new int[]{0,1,2,7,3,4,5,6}){final int bit=1<<i;CheckBox box=new CheckBox(activity);box.setText(protections[i]);box.setChecked((Control.transferProtect()&bit)!=0);transferProtection[i]=box;keepC.addView(box);
            box.setOnClickListener(v->Control.setTransferProtect(box.isChecked()?Control.transferProtect()|bit:Control.transferProtect()&~bit));}
        transferKeepList = button("Liste perso à garder"); keepC.addView(transferKeepList);
        transferKeepList.setOnClickListener(v -> TransferKeepDialog.show(activity, host::refresh));
        transferOverlaySwitch = switchRow(keepC, "Bouton overlay « tout garder »"); transferOverlaySwitch.setChecked(Control.transferOverlayEnabled());
        transferOverlaySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.transferOverlayEnabled())return; Control.setTransferOverlayEnabled(transferOverlaySwitch.isChecked()); host.refresh(); });
        caption(keepC, "Les espèces gardées ne sont jamais transférées automatiquement, toutes formes confondues. Le bouton flottant garde chaque capture (Tout garder) ou suit les règles ci-dessus.");

        LinearLayout pvpC = group(transfer, "Garder pour le PvP");
        // One row per league: its switch, then the rank it keeps (tap to cycle).
        for(int i=0;i<3;i++){final int league=i;
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(44));
            TextView label=new TextView(activity);label.setText("Garder les IV PvP "+Control.PVP_LEAGUES[i]+" ");label.setTextSize(14);row.addView(label,new LinearLayout.LayoutParams(0,-2,1f));
            Button rank=button("Top "+Control.pvpRank(i));pvpRank[i]=rank;row.addView(rank,new LinearLayout.LayoutParams(-2,-2));
            Switch sw=new Switch(activity);sw.setContentDescription("Garder les IV PvP "+Control.PVP_LEAGUES[i]+" ");sw.setChecked(Control.pvpKeep(i));pvpKeep[i]=sw;
            LinearLayout.LayoutParams swParams=new LinearLayout.LayoutParams(-2,-2);swParams.leftMargin=dp(8);row.addView(sw,swParams);
            sw.setOnCheckedChangeListener((v,checked)->{if(checked==Control.pvpKeep(league))return;Control.setPvpKeep(league,checked);host.refresh();});
            rank.setOnClickListener(v->{
                int[] ranks=Control.PVP_RANKS;int next=ranks[0];
                for(int j=0;j<ranks.length;j++)if(ranks[j]==Control.pvpRank(league)){next=ranks[(j+1)%ranks.length];break;}
                Control.setPvpRank(league,next);host.refresh();});
            pvpC.addView(row);}
        caption(pvpC, "Le PvP garde une capture dont les IV sont dans le top choisi d'une ligue activée, pour elle ou ses évolutions. L'espèce doit être dans le top "+Control.PVP_META+" de PvPoke pour la ligue, et rester sous la limite de PC de la ligue. Les rangs autorisent jusqu'au niveau 50.");

        LinearLayout evolveC = group(transfer, "Faire évoluer avant de transférer");
        evolveTransferSwitch = switchRow(evolveC, "Faire évoluer avant de transférer");
        evolveTransferSwitch.setChecked(Control.evolveTransferEnabled());
        evolveTransferSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.evolveTransferEnabled())return; Control.setEvolveTransferEnabled(evolveTransferSwitch.isChecked()); host.refresh(); });
        evolveTransferStages = button("Évolution : une fois"); evolveC.addView(evolveTransferStages);
        evolveTransferStages.setOnClickListener(v -> { Control.setEvolveTransferStages(Control.evolveTransferStages()==2?1:2); host.refresh(); });
        evolveTransferMode = button("S'applique à : règles de transfert actuelles"); evolveC.addView(evolveTransferMode);
        evolveTransferMode.setOnClickListener(v -> { Control.setEvolveTransferMode(Control.evolveTransferMode()==1?0:1); host.refresh(); });
        evolveTransferList = button("Espèces sélectionnées"); evolveC.addView(evolveTransferList);
        evolveTransferList.setOnClickListener(v -> TransferKeepDialog.show(activity, "Espèces sélectionnées", "espèces sélectionnées", Control::evolveTransferSpecies, Control::setEvolveTransferSpecies, host::refresh));
        caption(evolveC, "Nouvelles captures uniquement ; nécessite le transfert ci-dessus. Utilise les Bonbons pour les évolutions simples, sans objet ni condition spéciale. Les règles de conservation s'appliquent toujours. Si l'évolution est impossible, transfert normal. Le mode « espèces sélectionnées » garde toutes les espèces hors de sa liste.");

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
        LinearLayout page = page(MenuIcon.BAG, "Go Plus, PokéStops & objets");
        unavailableNote(page, false);

        LinearLayout vgpC=group(page,"Go Plus virtuel");
        vgpSwitch=switchRow(vgpC,"Overlay VGP");vgpSwitch.setChecked(Control.vgpOverlay());
        vgpSwitch.setOnCheckedChangeListener((b,checked)->{if(checked!=Control.vgpOverlay())Control.setVgpOverlay(checked);host.refresh();});
        String[] vgpLabels={"Pokémon à proximité","PokéStops à proximité","Notifications push"};
        for(int i=0;i<3;i++){final int bit=1<<i;CheckBox box=new CheckBox(activity);box.setText(vgpLabels[i]);box.setChecked((Control.vgpOptions()&bit)!=0);vgpChoices[i]=box;vgpC.addView(box);
            box.setOnClickListener(v->{Control.setVgpOptions(box.isChecked()?Control.vgpOptions()|bit:Control.vgpOptions()&~bit);
                if(bit==4&&box.isChecked()&&Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},7301);
            });
        }
        vgpCooldownStop=new CheckBox(activity);vgpCooldownStop.setText("Stop pendant le cooldown (estimé)");vgpCooldownStop.setContentDescription("Arrêter le Go Plus virtuel pendant le cooldown estimé");vgpCooldownStop.setChecked(Control.vgpStopOnCooldown());vgpC.addView(vgpCooldownStop);
        vgpCooldownStop.setOnClickListener(v->{if(vgpCooldownStop.isChecked()!=Control.vgpStopOnCooldown())Control.setVgpStopOnCooldown(vgpCooldownStop.isChecked());host.refresh();});
        vgpStatus=caption(vgpC,Control.vgpStatus());
        smartRefresh=SmartCatchSettings.populate(activity,vgpC);

        LinearLayout stops = group(page, "PokéStops"); summaryGated.add((View)stops.getParent());
        quickSpinSwitch = switchRow(stops, "Spin rapide"); quickSpinSwitch.setChecked(Control.quickSpinEnabled());
        quickSpinSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.quickSpinEnabled())return; Control.setQuickSpinEnabled(quickSpinSwitch.isChecked()); });
        quickSpinStatus = caption(stops, "");

        LinearLayout routesC = group(page, "Routes"); summaryGated.add((View)routesC.getParent());
        routeGiftSwitch = switchRow(routesC, "Échanger des cadeaux avec Mateo"); routeGiftSwitch.setChecked(Control.routeGiftExchange());
        routeGiftSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.routeGiftExchange())return; Control.setRouteGiftExchange(checked); });
        caption(routesC, "When Mateo offers a gift exchange on a Route, goes through it for you: taps him once you are in range, accepts, sends the suggested gift without a sticker and opens his gift. Pokémon trades are left to you.");

        // ----- Inventory (opens its modal directly) -----
        LinearLayout itemsC = group(page, "Objets"); summaryGated.add((View)itemsC.getParent());
        itemSettings = button("Jeter les objets auto"); itemsC.addView(itemSettings); itemSettings.setOnClickListener(v -> showItemSettings());
        itemStatus = caption(itemsC, "");
    }

    private void buildBattlesPage() {
        LinearLayout page = page(MenuIcon.BATTLE, "Combats");
        unavailableNote(page, false);
        LinearLayout battleC = plain(page); summaryGated.add(battleC);
        LinearLayout all = group(battleC, "Tous les combats");
        buildSmartBattle(all);
        buildHealing(all);

        // ----- Team Rocket -----
        LinearLayout rocketC = group(battleC, "Team GO Rocket");
        String[] rocketLabels={"Attaque rapide auto","Attaque chargée auto","Bouclier auto"};
        for(int i=0;i<3;i++){final int bit=1<<i;CheckBox box=new CheckBox(activity);box.setText(rocketLabels[i]);box.setChecked((Control.rocketOptions()&bit)!=0);rocketChoices[i]=box;rocketC.addView(box);
            box.setOnClickListener(v->Control.setRocketOptions(box.isChecked()?Control.rocketOptions()|bit:Control.rocketOptions()&~bit));}
        caption(rocketC, "Tant qu'une de ces options est active, le Pokémon suivant est choisi automatiquement quand l'un est K.O.");
        rocketStatus = caption(rocketC, "");

        LinearLayout gymC = group(battleC, "Arènes");
        launch(gymC, "Placement en arène & nourrissage", () -> GymAutomationSettings.show(activity));
        battleChoice(gymC,2,"Attaque rapide auto");
        battleChoice(gymC,3,"Attaque chargée auto");
        battleChoice(gymC,8,"Esquive auto");
        gymRepeatSwitch = switchRow(gymC, "Combattre jusqu'à la défaite"); gymRepeatSwitch.setChecked(Control.gymRepeatEnabled());
        gymRepeatSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.gymRepeatEnabled())return; Control.setGymRepeatEnabled(checked); });
        caption(gymC, "Après le début d'un combat d'arène, relance automatiquement la manche suivante depuis le même écran d'arène jusqu'à la défaite. Utilise tes réglages d'attaque et attend le soin intelligent s'il est réglé sur « Après les combats » ou les deux. Désactive ou quitte manuellement pour arrêter.");
        launch(gymC, "Nourrissage d'arène à distance", () -> AutomationPanel.showRemoteFeed(activity));

        LinearLayout raidC = group(battleC, "Raids");
        battleChoice(raidC,0,"Attaque rapide auto");
        battleChoice(raidC,1,"Attaque chargée auto");
        battleChoice(raidC,7,"Esquive auto");
        launch(raidC, "Auto-Raid", () -> AutoRaidDialog.show(activity));

        LinearLayout maxC = group(battleC, "Dynamax");
        battleChoice(maxC,4,"Attaque rapide auto");
        battleChoice(maxC,5,"Attaque chargée auto");
        battleChoice(maxC,6,"Attaque Max auto");
        battleChoice(maxC,9,"Esquive auto");
        caption(maxC,"Se déplace automatiquement vers les particules Max proches pendant le combat quand PGRazor est actif.");
        battleStatus = caption(battleC, "");
    }

    private void buildSmartBattle(LinearLayout owner) {
        smartBattleSwitch = switchRow(owner, "Combat intelligent"); smartBattleSwitch.setChecked(Control.smartBattle());
        smartBattleSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.smartBattle())return; Control.setSmartBattle(checked); refreshSmartBattle(); });
        caption(owner, "Choisit des counters contre le Pokémon affronté : remplit ton équipe avant les combats d'arène, de raid et Rocket, et passe à ton meilleur counter pendant les combats d'arène et Rocket.");
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
        healReviveSwitch = switchRow(owner, "Ranimer les Pokémon K.O."); healReviveSwitch.setChecked(Control.healRevive());
        healReviveSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.healRevive())return; Control.setHealRevive(checked); });
        healStatus = caption(owner, "");
        refreshHealing();
    }

    private void refreshHealing() {
        if(healSwitch==null)return;
        healSwitch.setChecked(Control.healEnabled()); healReviveSwitch.setChecked(Control.healRevive());
        set(healWhen,"Soigner : "+Control.HEAL_WHEN_LABELS[Control.healWhen()]);
        set(healThreshold,Control.healThreshold()>=100?"Heal when: Any HP missing":"Soigner si : sous "+Control.healThreshold()+" % de PV");
        set(healItems,Control.healFull()?"Items: Always heal fully":"Objets : intelligent (garde les grosses potions)");
        boolean on=Control.healEnabled();
        SettingsUi.enabled(healWhen,on); SettingsUi.enabled(healThreshold,on); SettingsUi.enabled(healItems,on); SettingsUi.enabled(healReviveSwitch,on);
        status(healStatus,Control.healStatus());
    }

    private void battleChoice(LinearLayout owner,int index,String label) {
        // Keep the persisted/native option bits unchanged when moving controls between groups.
        final int bit=1<<index;
        CheckBox box=new CheckBox(activity);box.setText(label);
        box.setChecked((Control.battleOptions()&bit)!=0);battleChoices[index]=box;owner.addView(box);
        box.setOnClickListener(v->Control.setBattleOptions(box.isChecked()?Control.battleOptions()|bit:Control.battleOptions()&~bit));
    }

    private void buildSocialPage() {
        LinearLayout page = page(MenuIcon.HEART, "Copain, Œufs & amis");
        unavailableNote(page, false);

        LinearLayout buddyC=group(page,"Moteur d'émotions du copain"); summaryGated.add((View)buddyC.getParent());
        buddyRefresh=BuddyEmotionSettings.populate(activity,buddyC);
        buddyStatus=caption(buddyC,"");

        LinearLayout eggsC = group(page, "Œufs");
        incubatorSwitch = switchRow(eggsC, "Incubation auto");
        incubatorSwitch.setChecked(Control.autoIncubate());
        incubatorSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (checked != Control.autoIncubate()) Control.setAutoIncubate(checked);
        });
        incubatorPriority = button("Priorité : distance la plus courte"); eggsC.addView(incubatorPriority);
        incubatorPriority.setOnClickListener(v -> { Control.setIncubateLongest(!Control.incubateLongest()); host.refresh(); });
        caption(eggsC, "Incubateur illimité uniquement");
        incubatorStatus = caption(eggsC, Control.incubatorStatus());

        LinearLayout giftC = group(page, "Cadeaux d'amis"); summaryGated.add((View)giftC.getParent());
        String[] giftLabels={"Ouvrir les cadeaux auto","Envoyer les cadeaux auto","Ajouter un autocollant possédé"};
        for(int i=0;i<3;i++){final int bit=1<<i;CheckBox box=new CheckBox(activity);box.setText(giftLabels[i]);box.setChecked((Control.giftOptions()&bit)!=0);giftChoices[i]=box;giftC.addView(box);box.setOnClickListener(v->Control.setGiftOptions(box.isChecked()?Control.giftOptions()|bit:Control.giftOptions()&~bit));}
        giftStatus = caption(giftC, "");
        launch(giftC, "Envoyer & ouvrir les cadeaux", () -> GiftBatchDialog.show(activity));
    }

    private void buildAutomationPage() {
        // One-shot manual tasks; mirrors the quick-menu Automation hub, plus Research.
        LinearLayout page = page(MenuIcon.AUTOMATION, "Automatisation");
        LinearLayout tasks = group(page, "Tâches ponctuelles");
        caption(tasks, "Chaque tâche s'exécute quand tu la lances.");
        launch(tasks, "Shundo Sniper (expérimental)", () -> ShundoSniper.show(activity));
        launch(tasks, "Shiny Scanner (expérimental)", () -> ShinyScanner.show(activity));
        launch(tasks, "Auto-Raid", () -> AutoRaidDialog.show(activity));
        launch(tasks, "Session d'Encens du jour", () -> AutomationPanel.showIncense(activity));
        launch(tasks, "Nourrissage d'arène à distance", () -> AutomationPanel.showRemoteFeed(activity));
        launch(tasks, "Envoyer & ouvrir les cadeaux", () -> GiftBatchDialog.show(activity));
        launch(tasks, "Évolution en masse", () -> BatchEvolutionSettings.show(activity));
        LinearLayout research = group(page, "Études de terrain");
        launch(research, "Études", () -> ResearchSettings.show(activity));
    }

    private void buildEventsPage() {
        LinearLayout page = page(MenuIcon.EVENTS, "Événements");
        LinearLayout events = group(page, "Réglages des événements");
        caption(events, "Réglages des événements temporaires.");
        launch(events, "Halloween 2026", () -> dev.pgrazor.events.EventPanel.showHalloween(activity, Control.darkTheme()));
        launch(events, "Go Wild November 2026", () -> dev.pgrazor.events.EventPanel.showGoWild(activity, Control.darkTheme()));
    }

    private void buildDisplayPage() {
        LinearLayout page = page(MenuIcon.SETTINGS, "Affichage & performances");
        LinearLayout look = group(page, "Interface");
        darkSwitch = switchRow(look, "Thème sombre"); darkSwitch.setChecked(Control.darkTheme());
        darkSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.darkTheme())return; Control.setDarkTheme(checked); host.applyTheme(); });
        uiScale=button("Taille de l'interface : "+Control.uiScalePercent()+"%");look.addView(uiScale);
        uiScale.setOnClickListener(v->{final int[] values={100,75,50,25};String[] labels={"100%","75%","50%","25%"};int selected=0;for(int i=0;i<values.length;i++)if(values[i]==Control.uiScalePercent())selected=i;
            new AlertDialog.Builder(dialogContext()).setTitle("Taille de l'overlay").setSingleChoiceItems(labels,selected,(dialog,which)->{host.releaseJoystick(true);Control.setUiScalePercent(values[which]);host.refresh();dialog.dismiss();}).setNegativeButton("Annuler",null).show();});
        skipCutscenesSwitch = switchRow(look, "Passer les cinématiques"); skipCutscenesSwitch.setChecked(Control.skipCutscenes());
        skipCutscenesSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked==Control.skipCutscenes())return; Control.setSkipCutscenes(checked); });
        cutsceneStatus = caption(look, "");  // shown only when the native module reports an error

        LinearLayout battery = group(page, "Batterie");
        caption(battery, "Baisse les graphismes pour économiser la batterie et garder le téléphone plus frais. Chaque option revient au réglage du jeu quand elle est désactivée.");
        batteryResSwitch = switchRow(battery, "Résolution réduite"); batteryResSwitch.setChecked(Control.batteryLowRes());
        batteryResSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryLowRes())Control.setBatteryLowRes(checked); });
        caption(battery, "Affiche la carte et les Pokémon en demi-résolution. Les menus et le texte restent nets.");
        batteryFpsSwitch = switchRow(battery, "Limiter à 20 FPS"); batteryFpsSwitch.setChecked(Control.batteryFps20());
        batteryFpsSwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryFps20())Control.setBatteryFps20(checked); });
        batteryQualitySwitch = switchRow(battery, "Graphismes bas"); batteryQualitySwitch.setChecked(Control.batteryLowQuality());
        batteryQualitySwitch.setOnCheckedChangeListener((v,checked) -> { if(checked!=Control.batteryLowQuality())Control.setBatteryLowQuality(checked); });
        caption(battery, "Désactive les ombres, le brouillard, les reflets d'eau, l'herbe en rencontre, le HDR et l'anticrénelage, et réduit les détails au loin.");

        LinearLayout menu = group(page,"Entrées du menu rapide");
        caption(menu,"Choisis les entrées affichées. Ça n'active ni ne désactive aucune fonction. « Paramètres » reste visible.");
        for(int i=0;i<QuickMenu.CUSTOMIZABLE.length;i++){
            final int bit=1<<i;
            CheckBox box=new CheckBox(activity);box.setText(QuickMenu.CUSTOMIZABLE[i]);
            box.setChecked(Control.quickMenuVisible(bit));quickMenuChoices[i]=box;menu.addView(box);
            box.setOnClickListener(v->{Control.setQuickMenuVisible(bit,box.isChecked());host.refresh();});
        }
    }

    private void buildAccountsPage() {
        LinearLayout page = page(MenuIcon.ACCOUNTS, "Comptes & sauvegarde");
        LinearLayout data = group(page, "Compte & données");
        launch(data, "Comptes", () -> AccountsDialog.show(activity));
        launch(data, "Exporter / importer les réglages", () -> {
            if(PgAssets.rooted())new SettingsBackup(activity).show();
            else activity.startActivity(new Intent(activity,LogActivity.class).putExtra("settingsBackup",true));
        });
        launch(data, "Journal / export", () -> LogActivity.open(activity));

        // ----- Diagnostics (status plumbing kept out of everyday settings) -----
        LinearLayout diag = section(page, "Diagnostics", false);
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

    /** Home-row summary: the features that are on, or a plain "All off". Arguments alternate name, state. */
    private static String active(Object... pairs){
        StringBuilder out=new StringBuilder();
        for(int i=0;i+1<pairs.length;i+=2)if(Boolean.TRUE.equals(pairs[i+1])){if(out.length()>0)out.append(" · ");out.append(pairs[i]);}
        return out.length()==0?"All off":out.toString();
    }
    private void summary(int index,String value){TextView view=pageSummaries.get(index);if(!value.contentEquals(view.getText()))view.setText(value);}
    private void refreshHome(){
        if(current>=0)return;
        summary(0,active("Spoofing",Control.desiredEnabled(),"Joystick",Control.joystickVisible(),"Marche à l'arrêt",Control.idleWalking(),"Cooldown",Control.cooldownOverlay(),"Heure locale",Control.localTimeOverlay()));
        summary(1,active("Capture rapide",Control.quickSummaryEnabled(),"Lancer auto",Control.autoThrow(),"Ball auto",Control.autoBall(),"Balle courbe",Control.throwCurve(),"Toujours toucher",Control.throwAlwaysHit(),"Baies",Control.encounterBerryEnabled()));
        summary(2,active("Transfert auto",Control.autoTransferEnabled(),"Évolution d'abord",Control.autoTransferEnabled()&&Control.evolveTransferEnabled(),"IV affichés",Control.ivCatchMode()!=0||Control.ivOwnedMode()!=0||Control.storageIvMode()!=0));
        summary(3,active("Overlay Go Plus",Control.vgpOverlay(),"Spin rapide",Control.quickSpinEnabled(),"Tri des objets",Control.itemTransferEnabled()));
        summary(4,active("Combat intelligent",Control.smartBattle(),"Soin intelligent",Control.healEnabled(),"Attaques auto",Control.battleOptions()!=0||Control.rocketOptions()!=0,"Arène en boucle",Control.gymRepeatEnabled()));
        summary(5,active("Moteur copain",BuddyEmotionSettings.enabled(),"Incubation auto",Control.autoIncubate(),"Ouvrir les cadeaux",(Control.giftOptions()&1)!=0,"Envoyer les cadeaux",(Control.giftOptions()&2)!=0));
        summary(6,AutoRaid.running()?"Auto-Raid running":ShundoSniper.active()?"Shundo Sniper running":ShinyScanner.enabled()?"Shiny Scanner on":"Shundo, scan shiny, raids, Encens, nourrissage d'arène, cadeaux, évolution, études");
        summary(7,"Halloween · Go Wild");
        summary(8,"Interface "+Control.uiScalePercent()+"% · "+(Control.darkTheme()?"Dark":"clair")+" "+(Control.skipCutscenes()?" · Skip cutscenes":""));
        summary(9,"Comptes, export / import, journal, diagnostics");
    }

    /** The root panel view; Pad owns attachment to the window and insets wiring. */
    View view(){return panel;}
    boolean isVisible(){return panel.getVisibility()==View.VISIBLE;}
    /** Bring the full-screen panel up; Pad applies current insets immediately after. */
    void show(){homeScroll=0;open(-1);panel.setVisibility(View.VISIBLE);panel.bringToFront();panel.requestApplyInsets();syncSystemBars();}
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

    // ------------------------------------------------------------------ settings search
    /** Applies a query immediately: the catalogue is small, so each keystroke filters the same list. */
    private void applySearch(String raw){
        String query=raw==null?"":raw.trim();
        if(query.contentEquals(searchQuery))return;
        searchQuery=query;
        clearSearch.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);
        if(query.isEmpty()){
            results.setVisibility(View.GONE);
            if(current<0)home.setVisibility(View.VISIBLE);
            else pages.get(current).setVisibility(View.VISIBLE);
            return;
        }
        ensureSearchIndex();
        java.util.List<SettingsSearch.Entry> hits=searchIndex.find(query);
        renderResults(hits);
        home.setVisibility(View.GONE);
        for(int i=0;i<pages.size();i++)pages.get(i).setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
        bodyScroll.scrollTo(0,0);
    }

    /** Empties the field without letting the watcher fight the page transition that is about to happen. */
    private void clearQuery(){
        if(searchField==null)return;
        if(searchField.length()>0){suppressSearch=true;searchField.setText("");suppressSearch=false;}
        searchQuery="";
        if(clearSearch!=null)clearSearch.setVisibility(View.GONE);
        if(results!=null)results.setVisibility(View.GONE);
    }

    private void ensureSearchIndex(){
        if(searchIndex!=null)return;
        searchIndex=new SettingsSearch();searchTargets.clear();searchSeen.clear();
        for(int i=0;i<pages.size();i++)collectSearch(pages.get(i),pageTitles.get(i),"",i);
    }

    /**
     * Scans one page once. Rows are recognised by their control, not by a second registry: a switch
     * row contributes its label, a checkbox or button contributes its own text. Group cards carry
     * their display label in {@link SettingsUi#TAG_GROUP}, so every hit can name its category.
     */
    private void collectSearch(View view,String category,String group,int pageIndex){
        Object tag=view.getTag(SettingsUi.TAG_GROUP);
        if(tag instanceof String)group=(String)tag;
        if(view instanceof ViewGroup){
            ViewGroup list=(ViewGroup)view;
            if(hasSwitch(list)){
                String label=firstText(list);
                if(label!=null)registerSearch(label,group,category,pageIndex,view);
                return;
            }
            for(int i=0;i<list.getChildCount();i++)collectSearch(list.getChildAt(i),category,group,pageIndex);
        }else if(view instanceof CheckBox){
            registerSearch(((CheckBox)view).getText().toString(),group,category,pageIndex,view);
        }else if(view instanceof Button){
            registerSearch(((Button)view).getText().toString(),group,category,pageIndex,view);
        }
    }

    private static boolean hasSwitch(ViewGroup group){
        for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i) instanceof Switch)return true;
        return false;
    }

    private static String firstText(ViewGroup group){
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(child instanceof TextView){
                CharSequence text=((TextView)child).getText();
                if(text!=null&&text.length()>0)return text.toString();
            }
        }
        return null;
    }

    private void registerSearch(String raw,String group,String category,int pageIndex,View target){
        String name=SettingsSearch.cleanName(raw);
        if(name.isEmpty())return;
        String key=pageIndex+"|"+name.toLowerCase(Locale.ROOT);
        if(!searchSeen.add(key))return;
        int id=searchIndex.add(name,group==null?"":group,category,pageIndex);
        while(searchTargets.size()<=id)searchTargets.add(null);
        searchTargets.set(id,target);
    }

    private void renderResults(java.util.List<SettingsSearch.Entry> hits){
        results.removeAllViews();
        if(hits.isEmpty()){
            LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(SettingsUi.cardBackground(activity));card.setTag(SettingsUi.TAG_CARD,Boolean.TRUE);card.setPadding(dp(16),dp(16),dp(16),dp(12));
            TextView empty=text("Aucun réglage trouvé",14,ink());empty.setTypeface(SettingsUi.medium());card.addView(empty);
            TextView hint=text("Essaie une fonction ou un mot-clé, par exemple Ball auto, cooldown ou IV.",12,muted());
            hint.setPadding(0,dp(4),0,dp(10));card.addView(hint);
            Button clear=button("Effacer la recherche");clear.setOnClickListener(v->searchField.setText(""));card.addView(clear);
            results.addView(card);
            return;
        }
        TextView count=text(hits.size()==1?"1 setting":hits.size()<=60?hits.size()+" settings":hits.size()+" réglages · 60 premiers",11,SettingsUi.faint());
        count.setTypeface(SettingsUi.mono());
        results.addView(count);
        int limit=Math.min(hits.size(),60);
        for(int i=0;i<limit;i++){
            final SettingsSearch.Entry entry=hits.get(i);
            final View target=searchTargets.get(entry.id);
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(SettingsUi.ripple(SettingsUi.cardBackground(activity)));
            row.setTag(SettingsUi.TAG_CARD_ROW,Boolean.TRUE);
            row.setClickable(true);row.setFocusable(true);row.setPadding(dp(14),dp(10),dp(10),dp(10));
            LinearLayout texts=new LinearLayout(activity);texts.setOrientation(LinearLayout.VERTICAL);
            TextView name=text(entry.name,14,ink());name.setTypeface(SettingsUi.medium());texts.addView(name);
            String meta=entry.group==null||entry.group.isEmpty()?entry.category:entry.group+" · "+entry.category;
            TextView where=text(meta,11,muted());where.setSingleLine(true);where.setEllipsize(android.text.TextUtils.TruncateAt.END);texts.addView(where);
            row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
            SettingsUi.Chevron chevron=new SettingsUi.Chevron(activity);row.addView(chevron,new LinearLayout.LayoutParams(dp(20),dp(20)));
            row.setOnClickListener(v->jumpTo(entry.page,target));
            LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.topMargin=i==0?dp(8):dp(6);results.addView(row,rowParams);
        }
    }

    /** Opens the owning page, scrolls the target row to the top of the body and flashes an accent wash. */
    private void jumpTo(int pageIndex,View target){
        open(pageIndex);
        if(target==null)return;
        bodyScroll.post(()->{
            // A match can live in a collapsed group: open it, then measure once layout has settled.
            for(View parent=target;parent!=null&&parent!=body;){
                Object header=parent.getTag(SettingsUi.TAG_SECTION_HEAD);
                if(header instanceof View&&parent.getVisibility()!=View.VISIBLE)((View)header).performClick();
                ViewParent next=parent.getParent();
                parent=next instanceof View?(View)next:null;
            }
            bodyScroll.post(()->reveal(pageIndex,target));
        });
    }

    private void reveal(int pageIndex,View target){
        if(current!=pageIndex||target.getParent()==null)return;
        int y=0;View view=target;
        while(view!=null&&view!=body){
            y+=view.getTop();
            ViewParent parent=view.getParent();
            view=parent instanceof View?(View)parent:null;
        }
        if(view!=body)return;
        bodyScroll.scrollTo(0,Math.max(0,y-dp(16)));
        target.setForeground(SettingsUi.rounded(activity,SettingsUi.accentSoft(),SettingsUi.RADIUS_CONTROL));
        handler.postDelayed(()->target.setForeground(null),900);
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
        panel.setBackgroundColor(SettingsUi.bg());styleTree(panel);
        if(panel.getVisibility()==View.VISIBLE)syncSystemBars();
        for(TextView head:groupHeads){head.setTextColor(muted());head.setTypeface(SettingsUi.medium());}
        for(TextView c:captions)c.setTextColor(muted());
        if(coords!=null)coords.setTextColor(ink());
        if(speedReadout!=null)speedReadout.setTextColor(accent());
        if(homeStatus!=null)homeStatus.setTextColor(muted());
        if(title!=null)title.setTextColor(ink());
        if(versionLabel!=null)versionLabel.setTextColor(SettingsUi.faint());
        if(discord!=null)discord.setTextColor(accent());
        for(LinearLayout row:homeRows)row.setBackground(SettingsUi.ripple(SettingsUi.cardBackground(activity)));
        for(RowList rows:rowLists)rows.setDividerDrawable(SettingsUi.hairline(activity));
        for(TextView name:homeNames){name.setTextColor(ink());name.setTypeface(SettingsUi.medium());}
        for(TextView summary:pageSummaries)summary.setTextColor(muted());
        if(searchBar!=null)searchBar.setBackground(SettingsUi.fieldBackground(activity));
        if(searchField!=null){searchField.setTextColor(ink());searchField.setHintTextColor(SettingsUi.faint());}
        if(homeDot!=null)homeDot.setColor(telemetryColor());
        for(MenuIcon icon:homeIcons)icon.invalidate();
        if(backIcon!=null)backIcon.invalidate();
        if(closeIcon!=null)closeIcon.invalidate();
        if(themeIcon!=null){themeIcon.setContentDescription(Control.darkTheme()?"Switch to light theme":"Passer au thème sombre");themeIcon.invalidate();}
    }

    private static int telemetryColor(){
        if(!Control.desiredEnabled())return SettingsUi.faint();
        return Control.engine.enabled?SettingsUi.success():SettingsUi.warn();
    }

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
        Runnable populate=()->{for(String row:Control.itemCatalog().split("\n")){String[] b=row.split("\\|",-1);if(b.length!=3)continue;int id;try{id=Integer.parseInt(b[0]);}catch(NumberFormatException invalid){continue;}if(boxes.containsKey(id)){boxes.get(id).setText(b[1]+" ("+b[2]+" dans le sac)");continue;}
            LinearLayout line=new LinearLayout(activity);line.setOrientation(LinearLayout.HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox box=new CheckBox(activity);box.setText(b[1]+" ("+b[2]+" dans le sac)");box.setChecked(selected.contains(id));line.addView(box,new LinearLayout.LayoutParams(0,-2,1));
            EditText cap=new EditText(dialogContext());cap.setInputType(InputType.TYPE_CLASS_NUMBER);cap.setSingleLine(true);cap.setHint("Max");cap.setText(String.valueOf(limits.containsKey(id)?limits.get(id):100));cap.setContentDescription("Maximum de "+b[1]+" à garder");SettingsUi.enabled(cap,enabled.isChecked()&&box.isChecked());box.setOnCheckedChangeListener((button,checked)->SettingsUi.enabled(cap,enabled.isChecked()&&checked));line.addView(cap,new LinearLayout.LayoutParams(dp(80),-2));rows.addView(line);styleTree(line);boxes.put(id,box);inputs.put(id,cap);
        }};populate.run();
        Runnable dependencies=()->{for(java.util.Map.Entry<Integer,CheckBox> entry:boxes.entrySet()){SettingsUi.enabled(entry.getValue(),enabled.isChecked());SettingsUi.enabled(inputs.get(entry.getKey()),enabled.isChecked()&&entry.getValue().isChecked());}};
        enabled.setOnCheckedChangeListener((v,on)->dependencies.run());dependencies.run();
        TextView status=new TextView(activity);status.setText(Control.itemStatus());form.addView(status);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Trier automatiquement l'inventaire").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer & appliquer",null).create();
        final Runnable[] poll=new Runnable[1];poll[0]=()->{if(!dialog.isShowing())return;Control.requestItemCatalog();populate.run();dependencies.run();status.setText(Control.itemStatus());handler.postDelayed(poll[0],1000);};
        itemDialogs.add(dialog);
        dialog.setOnDismissListener(d->{handler.removeCallbacks(poll[0]);itemDialogs.remove(dialog);});dialog.show();styleTree(form);handler.post(poll[0]);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{StringBuilder rules=new StringBuilder(),all=new StringBuilder();for(java.util.Map.Entry<Integer,EditText> e:inputs.entrySet()){int cap;try{cap=Integer.parseInt(e.getValue().getText().toString().trim());if(cap<0||cap>100000)throw new NumberFormatException();}catch(NumberFormatException invalid){e.getValue().setError("Entre 0 et 100000");return;}String rule=e.getKey()+":"+cap+";";all.append(rule);if(boxes.get(e.getKey()).isChecked())rules.append(rule);}
            Control.saveItemRules(enabled.isChecked(),rules.toString(),all.toString());dialog.dismiss();host.refresh();});
    }

    void refresh() {
        refreshHome();
        darkSwitch.setChecked(Control.darkTheme());
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
        refreshRootUpdates(false);
    }

    private void refreshRootUpdates(boolean now){
        if(rootUpdateStatus==null)return;
        long time=android.os.SystemClock.elapsedRealtime();
        if(!now&&time-rootUpdateRead<1500)return;
        rootUpdateRead=time;
        String status=RootUpdates.status(activity);
        if(!status.contentEquals(rootUpdateStatus.getText()))rootUpdateStatus.setText(status);
        if(rootUpdateAuto.isChecked()!=RootUpdates.automatic(activity))rootUpdateAuto.setChecked(RootUpdates.automatic(activity));
    }

    private void refreshDependencies(){
        for(CheckBox box:vgpChoices)SettingsUi.enabled(box,Control.vgpOverlay());
        SettingsUi.enabled(vgpCooldownStop,Control.vgpOverlay());smartRefresh.run();
        for(CheckBox box:transferProtection)SettingsUi.enabled(box,Control.autoTransferEnabled());
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
        SettingsUi.enabled(giftChoices[2],(Control.giftOptions()&2)!=0);
        SettingsUi.enabled((View)joystickSwitch.getParent(),Control.desiredEnabled());
        SettingsUi.enabled(mapTap,Control.desiredEnabled()&&Control.quickBallConfigured());
    }

    private void refreshLocationStatus() {
        if(uiScale!=null)set(uiScale,"Taille de l'interface : "+Control.uiScalePercent()+"%");
        set(coords,String.format(Locale.US,"%.6f, %.6f",Control.engine.latitude,Control.engine.longitude));
        set(speedReadout,String.format(Locale.US,"%.1f km/h",Control.engine.speedKmh));
        MotionEngine.RouteProgress route=Control.engine.routeProgress();
        locationSwitch.setChecked(Control.desiredEnabled());
        String movement=!Control.desiredEnabled()?"DEVICE LOCATION":Control.engine.enabled ? (Control.engine.walking ? "WALKING" : "ON") : "EN ATTENTE DE L'ADAPTATEUR";
        if (route.total>1 && route.active) movement=String.format(Locale.US,"ROUTE %d/%d  ·  %.0f m left",route.visited+1,route.total,route.remainingMeters);
        else if (route.total>1 && route.visited==route.total) movement="ON  ·  route terminée";
        set(homeStatus,!Control.desiredEnabled()?"Device location":movement);
        if(homeDot!=null)homeDot.setColor(telemetryColor());
        set(state,Control.connection + "\n" + movement + "  ·  lectures de coordonnées " + Control.latitudeReads + "/" + Control.longitudeReads);
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
        if(transferKeepList!=null){String kept=Control.transferKeepSpecies();int n=kept.isEmpty()?0:kept.split(",").length;set(transferKeepList,n>0?"Liste perso à garder ("+n+")":"Liste perso à garder");}
        if(transferOverlaySwitch!=null){transferOverlaySwitch.setChecked(Control.transferOverlayEnabled());transferOverlaySwitch.setEnabled(Control.autoTransferEnabled());transferOverlaySwitch.setAlpha(1f);}
        if(evolveTransferSwitch!=null)evolveTransferSwitch.setChecked(Control.evolveTransferEnabled());
        set(evolveTransferStages,"Évolution : "+(Control.evolveTransferStages()==2?"toutes les étapes disponibles":"une fois"));
        set(evolveTransferMode,"S'applique à : "+(Control.evolveTransferMode()==1?"selected species only":"règles de transfert actuelles"));
        if(evolveTransferList!=null){String sel=Control.evolveTransferSpecies();int n=sel.isEmpty()?0:sel.split(",").length;set(evolveTransferList,n>0?"Espèces sélectionnées ("+n+")":"Espèces sélectionnées");}
        status(transferStatus,Control.transferStatus());
    }

    private void refreshVgpStatus() {
        vgpSwitch.setChecked(Control.vgpOverlay());status(vgpStatus,Control.vgpStatus());
    }

    private void refreshIncubation() {
        incubatorSwitch.setChecked(Control.autoIncubate());
        incubatorSwitch.setEnabled(Control.incubatorAvailable());
        set(incubatorPriority,"Priorité : " + (Control.incubateLongest() ? "longest" : "shortest") + " ");
        status(incubatorStatus,Control.autoIncubate() ? Control.incubatorStatus() : "Off");
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
        set(itemSettings,"Tri des objets : "+(Control.itemTransferEnabled()?"ON":"OFF"));status(itemStatus,Control.itemStatus());
    }

    private void refreshQuickCatch() {
        berrySwitch.setChecked(Control.encounterBerryEnabled());
        set(berryPriority,"Priorité des baies : "+BerryPriorityPicker.summary());
        berryPicker.refresh();
        quickSpinSwitch.setChecked(Control.quickSpinEnabled());status(quickSpinStatus,Control.quickSpinStatus());
        set(quickSummaryLabel,Control.quickSummaryAutoDismiss() ? "Capture rapide" : "Résumé de capture plus rapide");
        quickSummarySwitch.setChecked(Control.quickSummaryEnabled());
        quickSummarySwitch.setEnabled(Control.quickSummaryConfigured());
        status(quickBallStatus,Control.quickBallStatus());
    }

    private void refreshLocationOptions() {
        for(int i=0;i<quickMenuChoices.length;i++)quickMenuChoices[i].setChecked(Control.quickMenuVisible(1<<i));
        idleWalkSwitch.setChecked(Control.idleWalking());idleWalkSwitch.setEnabled(Control.desiredEnabled());idleWalkSwitch.setAlpha(Control.desiredEnabled()?1f:.45f);
        set(mapTap,"Appui sur la carte : "+new String[]{"Ne rien faire","Marcher","Téléporter","Marcher / double appui pour téléporter"}[Control.mapTapMode()]);
        mapTap.setEnabled(Control.quickBallConfigured());
        spawnSwitch.setChecked(Control.spawnBooster());spawnSwitch.setEnabled(Control.spawnAllowed());status(spawnStatus,Control.spawnStatus());
        localTimeSwitch.setChecked(Control.localTimeOverlay());
        encounterCounterSwitch.setChecked(Control.encounterCounter());encounterOverlaySwitch.setChecked(Control.encounterOverlay());
        SettingsUi.enabled(encounterOverlaySwitch,Control.encounterCounter());
        if(current<0)set(title,EncounterCounter.title());
    }

    // Indexed by IV mode: 0 off, 1 level and attack/defense/stamina, 2 level and percent, 3 all three.
    private static final String[] IV_MODES={"Off","Niv · 15/14/12","Niv · IV","Niv · IV · 15/14/12","Niv · IV · 15/14/12 · PvP"};

    private void refreshThrows() {
        set(throwGrade,"Qualité du lancer : "+new String[]{"OFF","Bien","Super","Excellent"}[Control.throwRating()]);
        throwGrade.setEnabled(Control.quickBallConfigured()&&Control.throwVariety()==0);
        set(throwVariety,"Lancers variés : "+Control.THROW_VARIETY_LABELS[Control.throwVariety()]);
        throwVariety.setEnabled(Control.quickBallConfigured());
        curveSwitch.setChecked(Control.throwCurve());curveSwitch.setEnabled(Control.quickBallConfigured());
        alwaysHitSwitch.setChecked(Control.throwAlwaysHit());alwaysHitSwitch.setEnabled(Control.throwAlwaysHitReady() || Control.throwAlwaysHit());
        arSwitch.setChecked(Control.throwAr());arSwitch.setEnabled(Control.throwArReady());
        catchChanceSwitch.setChecked(Control.catchChanceShown());autoBallSwitch.setChecked(Control.autoBall());
        set(autoBallTarget,"Objectif Ball auto : "+Control.autoBallTarget()+"%");autoBallTarget.setEnabled(Control.autoBall());
        autoThrowSwitch.setChecked(Control.autoThrow());
        autoThrowMapSwitch.setChecked(Control.autoThrowOnMap());
        autoThrowMapSwitch.setEnabled(Control.autoThrow());
        set(ivCatchMode,"Rencontre : "+IV_MODES[Control.ivCatchMode()]);ivCatchMode.setEnabled(Control.quickBallConfigured());
        set(ivOwnedMode,"Écran des stats du Pokémon : "+IV_MODES[Control.ivOwnedMode()]);ivOwnedMode.setEnabled(Control.quickBallConfigured());
        set(storageIv,"Liste du stockage Pokémon : "+IV_MODES[Control.storageIvMode()]);
        String ivText=Control.ivDisplayStatus(), storageText=Control.storageIvStatus();
        status(ivStatus,ivText.isEmpty()?storageText:storageText.isEmpty()?ivText:ivText+"\n"+storageText);
    }
}
