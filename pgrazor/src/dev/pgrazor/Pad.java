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

/** An in-activity panel: no root or system-overlay permission needed. */
public final class Pad {
    final Activity activity;
    private final FrameLayout parent;
    // Every on-screen overlay lives in this full-screen layer so "Hide overlay" can hide them all at once;
    // the three-finger watcher brings them back.
    private final FrameLayout overlays;
    private View unhideWatcher;
    private boolean overlaysHidden;
    private AlertDialog hideOverlaysDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Stick stick;
    // The full-screen settings body (switches, captions, sections) owns its own construction/refresh.
    private final SettingsPanel settings;
    private View launcher;
    private VgpToggle vgpToggle;
    private int appliedUiScale;
    private int appliedWindowWidth,appliedWindowHeight;
    private final Rect appliedWindowBounds=new Rect();
    private LinearLayout joystickOverlay;
    private BerryOverlay encounterBerry;
    private TextView cooldownTimer;
    // One compact card groups the launcher, timer, encounters, VGP, gym, transfer, local time and status lines.
    private HudDock dock;private OverlayDrag dockDrag;
    private LinearLayout dockHeader;
    private TextView transferLabel;
    // Local wall-clock time at the spoofed coordinates (not device time), one line of the card.
    private LocalTimeOverlay localTime;
    private TextView vgpActivity, buddyEmotionStatus, catchChance;
    private OverlayDrag catchChanceDrag;
    private GymFeedOverlay gymFeedOverlay;
    private ShundoOverlay shundoOverlay;
    // Shiny Scanner: the latest shiny found, shown on the map until it expires or is tapped away; drag to move it.
    private TextView shinyBadge,shinyMark;private OverlayDrag shinyDrag;private AlertDialog shinyPopup;
    // Encounter counter (optional): this account's encounters in the last 24 hours, one line of the card.
    private TextView encounterBadge;
    // "Écran éteint" (ScreenPower): a black veil above the game and every PGRazor overlay. A volume
    // button (root helper) brings the screen back; its hint fades after a few seconds.
    private FrameLayout blackScreen;private TextView blackHint;private boolean blackShown;private String blackIssue="";
    private static final String BLACK_HINT="Écran éteint : le jeu continue de tourner.\nAppuie sur un bouton de volume pour rallumer.";
    private TextView gymAutomationStatus;
    private TextView automationStatus;
    private OverlayDrag shundoDrag;
    private VgpMarker vgpMarker;
    private MapCatchBadge mapCatchBadge;
    private QuickMenu quickMenu;
    // Keep All / transfer-rules toggle, one line of the card.
    private TransferToggle transferToggle;
    private GameRoutesOverlay gameRoutesOverlay;

    private int dp(float x){return SettingsUi.dp(activity,x);}
    private int ink(){return SettingsUi.ink();}
    private int muted(){return SettingsUi.muted();}
    private int surface(){return SettingsUi.surface();}
    private int accent(){return SettingsUi.accent();}
    private GradientDrawable rounded(int color,int radius){return SettingsUi.rounded(activity,color,radius);}
    private void styleTree(View view){SettingsUi.styleTree(activity,view);}
    private Context dialogContext(){return SettingsUi.dialogContext(activity);}
    private TextView text(String label,int size,int color){return SettingsUi.text(activity,label,size,color);}
    private Button button(String label){return SettingsUi.button(activity,label);}

    /** Re-theme the overlays Pad owns, then the settings body. Invoked on dark/light toggle. */
    private void applyTheme(){
        settings.applyTheme();
        if(launcher!=null)launcher.invalidate();
        if(joystickOverlay!=null){joystickOverlay.setBackground(null);stick.setAlpha(0.78f);styleTree(joystickOverlay);stick.invalidate();}
        if(encounterBerry!=null)encounterBerry.invalidate();
        if(dock!=null){dock.setBackground(SettingsUi.shape(activity,surface(),SettingsUi.RADIUS_CARD,SettingsUi.border()));dock.setDividerDrawable(SettingsUi.hairline(activity));}
        if(localTime!=null){localTime.applyTheme();localTime.setBackground(null);}
        // Status pill colours depend only on the theme; refresh() must not reallocate them every tick.
        for(TextView pill:new TextView[]{catchChance,shinyBadge}){
            if(pill!=null){pill.setBackground(SettingsUi.pillBackground(activity));pill.setTextColor(ink());}
        }
        for(TextView line:new TextView[]{gymAutomationStatus,buddyEmotionStatus,automationStatus,vgpActivity,encounterBadge,transferLabel}){
            if(line!=null){line.setBackground(null);line.setTextColor(line==encounterBadge?muted():ink());}
        }
        if(transferToggle!=null)transferToggle.invalidate();
        if(gameRoutesOverlay!=null)gameRoutesOverlay.invalidate();
        if(quickMenu!=null)quickMenu.applyTheme();
    }
    private void draggable(View handle,View target,boolean clickable){draggable(handle,target,clickable,null);}
    /** As above; a still press held for 600 ms runs longPress instead of the click (the R launcher turns the screen off). */
    private void draggable(View handle,View target,boolean clickable,Runnable longPress){
        handle.setOnTouchListener(new View.OnTouchListener(){float downX,downY,originX,originY;boolean moved,longFired;
            final Runnable hold=()->{if(!moved&&longPress!=null){longFired=true;handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);longPress.run();}};
            public boolean onTouch(View v,MotionEvent e){int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();originX=target.getX();originY=target.getY();moved=false;longFired=false;if(longPress!=null)handler.postDelayed(hold,600);return true;}
                if(a==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.hypot(dx,dy)>dp(6)){moved=true;handler.removeCallbacks(hold);}if(moved){Rect safe=UiViewport.bounds(parent);target.setX(UiViewportPolicy.clamp(originX+dx,safe.left,safe.right,OverlayDrag.visualWidth(target)));target.setY(UiViewportPolicy.clamp(originY+dy,safe.top,safe.bottom,OverlayDrag.visualHeight(target)));}return true;}
                if(a==MotionEvent.ACTION_UP){handler.removeCallbacks(hold);if(!moved&&!longFired&&clickable)v.performClick();return true;}
                if(a==MotionEvent.ACTION_CANCEL)handler.removeCallbacks(hold);
                return true;}
        });
    }
    /** Fixed card width: long texts wrap onto more lines instead of widening the card. */
    private static final int DOCK_WIDTH_DP=216;
    /** A status line inside the card: small monospace text, no pill of its own, never cut (it wraps). */
    private TextView dockText(String label){
        TextView t=text(label,11,ink());t.setTypeface(SettingsUi.mono());t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    /** One line of the card: an optional square icon, then its text filling the rest of the fixed width. */
    private LinearLayout dockRow(View icon,int iconDp,View label){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(4),0,dp(4));
        if(icon!=null)row.addView(icon,new LinearLayout.LayoutParams(dp(iconDp),dp(iconDp)));
        if(label!=null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);lp.leftMargin=icon!=null?dp(8):0;row.addView(label,lp);}
        dock.addView(row,new LinearLayout.LayoutParams(-1,-2));
        return row;
    }
    /** A line shows while any of its views does; the card's dividers skip hidden lines. */
    private void syncDock(){
        if(dock==null)return;
        for(int i=0;i<dock.getChildCount();i++){
            if(quickMenu!=null&&dock.getChildAt(i)==quickMenu.view())continue; // shown or hidden by the R button only
            ViewGroup row=(ViewGroup)dock.getChildAt(i);boolean any=false;
            for(int j=0;j<row.getChildCount()&&!any;j++)any=row.getChildAt(j).getVisibility()==View.VISIBLE;
            int want=any?View.VISIBLE:View.GONE;if(row.getVisibility()!=want)row.setVisibility(want);
        }
        if(dockDrag!=null)dockDrag.refreshBounds();
    }
    private String transferShort(){
        boolean keep=Control.transferKeepAll(),master=Control.autoTransferEnabled();
        return keep?"Tout garder":(master?"Transfert : règles":"Transfert : off");
    }
    /** "Éteindre l'écran" from the quick menu, the R launcher (long press) or the Shundo Sniper card. */
    void turnScreenOff(){
        if(closed||activity.isFinishing()||activity.isDestroyed())return;
        if(quickMenu!=null&&quickMenu.isShowing())quickMenu.hide();
        stick.release(true);
        ScreenPower.turnOff(activity);
        refresh();
    }
    private final class RazorIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RazorIcon(Context c){super(c);setContentDescription("Ouvrir les paramètres PGRazor. Glisse pour déplacer.");setFocusable(true);setClickable(true);}
        protected void onDraw(Canvas c){super.onDraw(c);float scale=Math.min(getWidth(),getHeight())/48f;c.save();c.scale(scale,scale);
            paint.setStyle(Paint.Style.FILL);paint.setColor(surface());c.drawCircle(24,24,22.5f,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f);paint.setColor(SettingsUi.border());c.drawCircle(24,24,22.1f,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(ink());
            Path r=new Path();r.moveTo(13,11);r.lineTo(29,11);r.lineTo(36,18);r.lineTo(29,26);r.lineTo(36,37);r.lineTo(26,37);r.lineTo(19,26);r.lineTo(19,37);r.lineTo(12,37);r.lineTo(17,19);r.lineTo(24,19);r.lineTo(23,22);r.lineTo(27,22);r.lineTo(30,18);r.lineTo(27,16);r.lineTo(12,16);r.close();c.drawPath(r,paint);
            // Status dot: the launcher doubles as a quiet "spoofing on" indicator.
            paint.setColor(Control.desiredEnabled()?accent():SettingsUi.faint());c.drawCircle(38,38,3.6f,paint);
            c.restore();}
    }

    private long lastTick = SystemClock.elapsedRealtime(), lastLog, lastPositionCheck;
    private String lastPosition = "";
    private boolean closed, lastEnabled, launcherSpoofing;
    private AlertDialog stopRouteDialog;
    public Pad(Activity host) {
        activity = host;
        parent = (FrameLayout)host.getWindow().getDecorView();
        // Build the settings body first (not yet attached); it calls back through the narrow host
        // interface for the few overlay/joystick/engine actions Pad still owns.
        settings = new SettingsPanel(host, new SettingsPanel.Host(){
            public void refresh(){ Pad.this.refresh(); }
            public void applyTheme(){ Pad.this.applyTheme(); }
            public void releaseJoystick(boolean preserveWalk){ stick.release(preserveWalk); }
            public void setJoystickVisible(boolean visible){ stick.release(true); Control.setJoystickVisible(visible); joystickOverlay.setVisibility(visible?View.VISIBLE:View.GONE); }
            public void openMap(){ openWorldMap(); }
            public void editLocation(){ edit(); }
        });

        stick = new Stick(host);
        joystickOverlay = new LinearLayout(host); joystickOverlay.setOrientation(LinearLayout.VERTICAL);
        TextView grip = text("•••", 10, SettingsUi.faint()); grip.setGravity(Gravity.CENTER); grip.setLetterSpacing(0.18f); joystickOverlay.addView(grip, new LinearLayout.LayoutParams(-1, dp(28)));
        joystickOverlay.addView(stick, new LinearLayout.LayoutParams(dp(136), dp(136))); draggable(grip, joystickOverlay, false);

        // The settings panel is a full-screen modal; it consumes taps so the game behind it stays inert.
        parent.addView(settings.view(), new FrameLayout.LayoutParams(-1, -1));
        overlays=new FrameLayout(host);parent.addView(overlays,new FrameLayout.LayoutParams(-1,-1));
        blackScreen=new FrameLayout(host);blackScreen.setBackgroundColor(Color.BLACK);blackScreen.setVisibility(View.GONE);
        blackScreen.setClickable(true);blackScreen.setContentDescription("Écran éteint. Appuie sur un bouton de volume pour rallumer.");
        blackHint=text(BLACK_HINT,13,Color.rgb(140,140,140));blackHint.setGravity(Gravity.CENTER);
        blackScreen.addView(blackHint,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
        // Touches only swallow input and show the hint: the volume buttons (root helper) bring the screen back.
        blackScreen.setOnTouchListener((v,e)->{
            if(e.getActionMasked()!=MotionEvent.ACTION_DOWN)return true;
            if(blackHint!=null&&blackHint.getVisibility()!=View.VISIBLE){blackHint.setVisibility(View.VISIBLE);handler.postDelayed(()->{if(blackHint!=null)blackHint.setVisibility(View.GONE);},2500);}
            return true;
        });

        FrameLayout.LayoutParams joyLayout=new FrameLayout.LayoutParams(dp(136),-2,Gravity.LEFT|Gravity.BOTTOM);joyLayout.leftMargin=dp(16);joyLayout.bottomMargin=dp(96);overlays.addView(joystickOverlay,joyLayout);

        // Compact catching-berry overlay; the popup chooses owned berries and Off.
        encounterBerry=new BerryOverlay(activity,this::refresh);
        FrameLayout.LayoutParams berryLayout=new FrameLayout.LayoutParams(dp(90),dp(50),Gravity.RIGHT|Gravity.CENTER_VERTICAL);berryLayout.rightMargin=dp(10);overlays.addView(encounterBerry,berryLayout);encounterBerry.setVisibility(View.GONE);

        cooldownTimer=text("Historique  --:--",12,accent());cooldownTimer.setTypeface(SettingsUi.mono());cooldownTimer.setGravity(Gravity.CENTER_VERTICAL);cooldownTimer.setClickable(true);cooldownTimer.setOnClickListener(v->Cooldown.show(activity));
        cooldownTimer.setContentDescription("Historique des actions et temps d'attente. Touche pour ouvrir.");

        // Small local-time line: the wall-clock time at the spoofed coordinates, formatted offline by the view.
        localTime=new LocalTimeOverlay(host);localTime.setVisibility(View.GONE);localTime.setTextSize(11);localTime.setMinWidth(0);localTime.setMinimumWidth(0);localTime.setPadding(0,0,0,0);

        // Window attachment: the panel pads itself inside the system bars/IME as insets arrive.
        View panelView=settings.view();
        panelView.setOnApplyWindowInsetsListener((view,insets) -> {settings.applyInsets(insets);return insets;});
        panelView.requestApplyInsets();

        vgpToggle=new VgpToggle(host);vgpToggle.setOnClickListener(v->{Control.setVgpEnabled(!Control.vgpEnabled());refresh();});
        vgpActivity=dockText("VGP");vgpActivity.setVisibility(View.GONE);vgpActivity.setOnClickListener(v->vgpToggle.performClick());
        buddyEmotionStatus=dockText("EP : en attente");buddyEmotionStatus.setVisibility(View.GONE);
        buddyEmotionStatus.setOnClickListener(v->BuddyEmotionSettings.show(activity));
        gymFeedOverlay=new GymFeedOverlay(activity);
        gymAutomationStatus=dockText("Off");gymAutomationStatus.setOnClickListener(v->gymFeedOverlay.performClick());
        automationStatus=dockText("");automationStatus.setVisibility(View.GONE);
        automationStatus.setOnClickListener(v->AutomationPanel.show(activity));
        // Catch chance for the open encounter, under the game's nameplate; drag to move it.
        catchChance=text("",12,ink());catchChance.setPadding(dp(10),dp(6),dp(10),dp(6));catchChance.setMaxLines(2);catchChance.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams catchLayout=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);catchLayout.topMargin=dp(400);
        overlays.addView(catchChance,catchLayout);catchChance.setVisibility(View.GONE);catchChanceDrag=OverlayDrag.attach(overlays,catchChance,"catch-chance");
        mapCatchBadge=new MapCatchBadge(host);FrameLayout.LayoutParams mapCatchLayout=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.TOP|Gravity.LEFT);mapCatchLayout.leftMargin=dp(72);mapCatchLayout.topMargin=dp(100);overlays.addView(mapCatchBadge,mapCatchLayout);mapCatchBadge.setVisibility(View.GONE);
        vgpMarker=new VgpMarker(host);overlays.addView(vgpMarker,new FrameLayout.LayoutParams(dp(38),dp(46)));vgpMarker.setVisibility(View.GONE);
        // Shundo Sniper card: status, counts and Start/Stop; drag to move it.
        shundoOverlay=new ShundoOverlay(activity);FrameLayout.LayoutParams shundoLayout=new FrameLayout.LayoutParams(dp(290),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);shundoLayout.topMargin=dp(96);
        overlays.addView(shundoOverlay,shundoLayout);shundoOverlay.setVisibility(View.GONE);shundoDrag=OverlayDrag.attach(overlays,shundoOverlay,"shundo");
        EncounterCounter.prepare(activity);encounterBadge=dockText("");encounterBadge.setSingleLine(true);encounterBadge.setTextColor(muted());encounterBadge.setVisibility(View.GONE);
        shinyMark=text("✨",19,Color.WHITE);shinyMark.setGravity(Gravity.CENTER);shinyMark.setShadowLayer(dp(4),0,0,Color.BLACK);shinyMark.setClickable(false);shinyMark.setFocusable(false);
        shinyMark.setContentDescription("Pokémon shiny ici");overlays.addView(shinyMark,new FrameLayout.LayoutParams(dp(32),dp(32)));shinyMark.setVisibility(View.GONE);
        ShinyScanner.prepare(activity);shinyBadge=text("",13,ink());shinyBadge.setPadding(dp(12),dp(8),dp(12),dp(8));shinyBadge.setMaxLines(2);shinyBadge.setBackground(rounded(surface(),10));
        FrameLayout.LayoutParams shinyLayout=new FrameLayout.LayoutParams(dp(250),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);shinyLayout.topMargin=dp(150);
        overlays.addView(shinyBadge,shinyLayout);shinyBadge.setVisibility(View.GONE);shinyBadge.setOnClickListener(v->{ShinyScanner.showFound();refresh();});shinyDrag=OverlayDrag.attach(overlays,shinyBadge,"shiny");
        transferToggle=new TransferToggle(host);transferToggle.setOnClickListener(v->{Control.setTransferKeepAll(!Control.transferKeepAll());refresh();});
        transferToggle.setVisibility(View.GONE);transferLabel=dockText("");transferLabel.setOnClickListener(v->transferToggle.performClick());
        gameRoutesOverlay=new GameRoutesOverlay(activity);gameRoutesOverlay.setVisibility(View.GONE);
        launcher=new RazorIcon(host);
        launcher.setContentDescription("Ouvrir le menu rapide PGRazor. Glisse pour déplacer. Appui long : éteindre l'écran.");

        // The card: header (launcher, history timer, game routes), then one line per active feature.
        dock=new HudDock(host);dock.setPadding(dp(8),dp(4),dp(8),dp(4));
        dock.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        // Header: logo, history timer and the 24 h encounter count on one line (the count keeps its width, the timer wraps if it must).
        dockHeader=dockRow(launcher,30,cooldownTimer);
        LinearLayout.LayoutParams countSlot=new LinearLayout.LayoutParams(-2,-2);countSlot.leftMargin=dp(6);dockHeader.addView(encounterBadge,countSlot);
        LinearLayout.LayoutParams routesSlot=new LinearLayout.LayoutParams(dp(24),dp(24));routesSlot.leftMargin=dp(4);dockHeader.addView(gameRoutesOverlay,routesSlot);
        dockRow(null,0,localTime);
        dockRow(vgpToggle,30,vgpActivity);
        dockRow(gymFeedOverlay,30,gymAutomationStatus);
        dockRow(transferToggle,30,transferLabel);
        dockRow(null,0,buddyEmotionStatus);
        dockRow(null,0,automationStatus);
        FrameLayout.LayoutParams dockLayout=new FrameLayout.LayoutParams(dp(DOCK_WIDTH_DP),-2,Gravity.TOP|Gravity.LEFT);dockLayout.leftMargin=dp(12);dockLayout.topMargin=dp(100);
        overlays.addView(dock,dockLayout);dockDrag=OverlayDrag.attach(overlays,dock,"hud");dock.setDrag(dockDrag);
        // The R launcher now toggles the compact quick menu; Settings there opens this panel full screen.
        quickMenu=new QuickMenu(host,new QuickMenu.Listener(){
            public void onMap(){openWorldMap();}
            public double onCycleWalkSpeed(){double s=Control.cycleWalkSpeed();refresh();return s;}
            public void onAutoWalk(){stick.release(true);AutoWalkDialog.show(activity);}
            public void onGameRoutes(){stick.release(true);GameRoutesDialog.show(activity);}
            public void onFeeds(){stick.release(true);FeedChooser.show(activity);}
            public void onFavorites(){QuickLocationDialogs.showFavorites(activity,Pad.this::quickTeleport);}
            public void onTeleport(){stick.release(true);QuickLocationDialogs.showTeleport(activity,Pad.this::quickTeleport,Pad.this::quickWalk);}
            public void onHotspots(){stick.release(true);HotspotsDialog.showQuick(activity,Pad.this::quickTeleport,Pad.this::quickWalk);}
            public void onSettingsPage(int index){openSettings(index);}
            public void onSearch(){openSettings(-1);}
            public void onHideOverlays(){confirmHideOverlays();}
            public void onScreenOff(){turnScreenOff();}
        },settings.pageNames(),settings.pageIcons());
        // The menu unfolds inside the card, right under the launcher line, so the R button stays visible to close it.
        dock.addView(quickMenu.view(),1,new LinearLayout.LayoutParams(-1,-2));
        quickMenu.setOnToggle(this::refresh);
        // Tap opens the quick menu, a long press turns the screen off; dragging moves the whole card (HudDock).
        launcher.setOnClickListener(v->{if(quickMenu!=null)quickMenu.toggle();});
        launcher.setOnLongClickListener(v->{turnScreenOff();return true;});

        // Overlay status reads as telemetry: one monospace size, no per-tick font work.
        for(TextView pill:new TextView[]{catchChance}){
            if(pill==null)continue;
            pill.setTextSize(11);pill.setTypeface(SettingsUi.mono());
        }
        settings.hide();joystickOverlay.setVisibility(Control.joystickVisible()?View.VISIBLE:View.GONE);applyTheme();
        unhideWatcher=new ThreeFingerWatcher(host);parent.addView(unhideWatcher,new FrameLayout.LayoutParams(-1,-1));
        parent.addView(blackScreen,new FrameLayout.LayoutParams(-1,-1));
        refresh();parent.post(this::applyMapScale); handler.post(tick);
    }
    /** Decode a bundled map-icon once; returns null (never throws, never hits the network) if unavailable. */
    private Bitmap decodeMapIcon(String name){
        try(java.io.InputStream in=PgAssets.open(activity, "pgrazor/map-icons/"+name)){return BitmapFactory.decodeStream(in);}
        catch(java.io.IOException|RuntimeException ignored){return null;}
    }
    private final class VgpToggle extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint art=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF dst=new RectF();
        // Exact supplied PokeMiners Plus icons, decoded once with alpha intact; a null falls back to the vector glyph.
        private final Bitmap connected=decodeMapIcon("vgp-connected.png"),disconnected=decodeMapIcon("vgp-disconnected.png");
        VgpToggle(Context context){super(context);setFocusable(true);setClickable(true);}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);boolean on=Control.vgpEnabled();canvas.save();canvas.scale(getWidth()/54f,getHeight()/54f);
            paint.setStyle(Paint.Style.FILL);paint.setColor(surface());canvas.drawRoundRect(1,1,53,53,13,13,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(on?accent():SettingsUi.border());canvas.drawRoundRect(1,1,53,53,13,13,paint);
            Bitmap icon=on?connected:disconnected;
            if(icon!=null&&icon.getWidth()>0&&icon.getHeight()>0){float box=30f,ratio=Math.min(box/icon.getWidth(),box/icon.getHeight()),w=icon.getWidth()*ratio,h=icon.getHeight()*ratio;dst.set(27-w/2f,20-h/2f,27+w/2f,20+h/2f);canvas.drawBitmap(icon,null,dst,art);}
            else{paint.setColor(on?SettingsUi.success():SettingsUi.faint());canvas.drawCircle(27,19,12,paint);paint.setColor(surface());paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);canvas.drawArc(19,11,35,27,-55,290,false,paint);canvas.drawLine(27,7,27,19,paint);}
            paint.setStyle(Paint.Style.FILL);paint.setColor(on?accent():SettingsUi.faint());paint.setTextSize(9.5f);paint.setTypeface(SettingsUi.mono());paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(on?"ON":"OFF",27,49,paint);
            canvas.restore();}
    }
    private final class VgpMarker extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        VgpMarker(Context context){super(context);setClickable(false);setFocusable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/38f,getHeight()/46f);
            paint.setStyle(Paint.Style.FILL);paint.setColor(surface());canvas.drawCircle(19,18,17,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(SettingsUi.border());canvas.drawCircle(19,18,16.4f,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Control.vgpTargetSpin()?accent():SettingsUi.danger());canvas.drawCircle(19,18,14,paint);
            paint.setColor(Color.WHITE);
            if(Control.vgpTargetSpin()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);canvas.drawCircle(19,18,8,paint);canvas.drawLine(19,5,19,10,paint);canvas.drawLine(19,26,19,31,paint);canvas.drawLine(6,18,11,18,paint);canvas.drawLine(27,18,32,18,paint);}
            else{paint.setStrokeWidth(3);canvas.drawLine(5,18,33,18,paint);canvas.drawCircle(19,18,5,paint);paint.setColor(SettingsUi.ink());canvas.drawCircle(19,18,2.5f,paint);}
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);Path arrow=new Path();arrow.moveTo(13,36);arrow.lineTo(25,36);arrow.lineTo(19,44);arrow.close();canvas.drawPath(arrow,paint);canvas.restore();
        }
    }
    /**
     * Compact overlay for the transfer policy with three clearly distinct states: KEEP (Keep All on,
     * manual catches are kept), XFER (Keep All off and the auto-transfer master on, so the rules run),
     * and OFF (auto-transfer master off, so nothing transfers regardless). Tap toggles Keep All only;
     * it never re-enables the master.
     */
    private final class TransferToggle extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        TransferToggle(Context c){super(c);setFocusable(true);setClickable(true);}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);
            boolean keep=Control.transferKeepAll(),master=Control.autoTransferEnabled();
            int tint=keep?SettingsUi.success():(master?SettingsUi.warn():SettingsUi.faint());
            canvas.save();canvas.scale(getWidth()/54f,getHeight()/54f);
            paint.setStyle(Paint.Style.FILL);paint.setColor(surface());canvas.drawRoundRect(1,1,53,53,13,13,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);paint.setColor(SettingsUi.border());canvas.drawRoundRect(1,1,53,53,13,13,paint);
            // Shield outline frames the state glyph.
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.4f);paint.setColor(tint);paint.setStrokeCap(Paint.Cap.ROUND);
            Path shield=new Path();shield.moveTo(27,7);shield.lineTo(40,12);shield.lineTo(40,25);shield.cubicTo(40,34,34,39,27,42);shield.cubicTo(20,39,14,34,14,25);shield.lineTo(14,12);shield.close();canvas.drawPath(shield,paint);
            if(keep){canvas.drawLine(21,24,25,29,paint);canvas.drawLine(25,29,34,18,paint);} // check
            else if(master){canvas.drawLine(19,24,33,24,paint);Path a=new Path();a.moveTo(29,19);a.lineTo(35,24);a.lineTo(29,29);canvas.drawPath(a,paint);} // transfer-out arrow
            else{canvas.drawLine(21,24,33,24,paint);} // dash: master off
            paint.setStyle(Paint.Style.FILL);paint.setColor(tint);paint.setTextSize(9);paint.setTypeface(SettingsUi.mono());paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(keep?"KEEP":(master?"XFER":"OFF"),27,51,paint);
            canvas.restore();}
    }
    private String transferToggleDescription(){
        boolean keep=Control.transferKeepAll(),master=Control.autoTransferEnabled();
        String state=keep?"« Tout garder » activé ; les captures manuelles sont gardées":(master?"Les règles de transfert normales s'appliquent":"Le transfert auto est désactivé");
        return "Transfert des rencontres manuelles : "+state+". Touche pour "+(keep?"appliquer les règles de transfert normales":"garder les captures manuelles")+". Glisse pour déplacer.";
    }

    private void applyMapScale(){
        if(launcher==null||launcher.getWidth()==0||parent.getWidth()==0)return;
        Rect safe=UiViewport.bounds(parent);
        int percent=Control.uiScalePercent();
        if(appliedUiScale==percent&&appliedWindowWidth==parent.getWidth()&&appliedWindowHeight==parent.getHeight()&&appliedWindowBounds.equals(safe))return;
        Rect previousSafe=appliedWindowBounds.isEmpty()?safe:new Rect(appliedWindowBounds);
        appliedWindowBounds.set(safe);appliedWindowWidth=parent.getWidth();appliedWindowHeight=parent.getHeight();
        appliedUiScale=percent;float scale=percent/100f;
        for(View view:new View[]{dock,joystickOverlay,vgpMarker,mapCatchBadge,shundoOverlay,shinyBadge,shinyMark}){
            if(view==null)continue;
            float x=view.getX(),y=view.getY();
            int oldWidth=OverlayDrag.visualWidth(view),oldHeight=OverlayDrag.visualHeight(view);
            float fitted=UiViewportPolicy.fit(scale,view.getWidth(),view.getHeight(),safe.width(),safe.height());
            view.setPivotX(0);view.setPivotY(0);view.setScaleX(fitted);view.setScaleY(fitted);
            view.setX(UiViewportPolicy.resizePosition(x,previousSafe.left,previousSafe.right,oldWidth,safe.left,safe.right,OverlayDrag.visualWidth(view)));
            view.setY(UiViewportPolicy.resizePosition(y,previousSafe.top,previousSafe.bottom,oldHeight,safe.top,safe.bottom,OverlayDrag.visualHeight(view)));
        }
        if(dockDrag!=null)dockDrag.refreshBounds();
        if(encounterBerry!=null)encounterBerry.applyUiScale(scale);
    }
    private void refresh() {
        applyMapScale();
        if(gymFeedOverlay!=null)gymFeedOverlay.refresh(!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing()));
        boolean black=ScreenPower.off();
        if(black)ScreenPower.pollWake(activity);
        if(blackScreen!=null&&black!=blackShown){
            blackShown=black;blackScreen.setVisibility(black?View.VISIBLE:View.GONE);
            if(black){blackIssue="";blackHint.setText(BLACK_HINT);blackScreen.bringToFront();blackHint.setVisibility(View.VISIBLE);handler.postDelayed(()->{if(blackHint!=null)blackHint.setVisibility(View.GONE);},3000);}
        }
        if(black&&blackHint!=null&&!ScreenPower.rootIssue().equals(blackIssue)){
            // The root helper did not switch the panel off: say so on the veil for a few seconds.
            blackIssue=ScreenPower.rootIssue();
            blackHint.setText(blackIssue.isEmpty()?BLACK_HINT:BLACK_HINT+"\n\n"+blackIssue);
            if(!blackIssue.isEmpty()){blackHint.setVisibility(View.VISIBLE);handler.postDelayed(()->{if(blackHint!=null)blackHint.setVisibility(View.GONE);},8000);}
        }
        if(encounterBadge!=null){String count=!Control.encounterOverlay()||settings.isVisible()?"":EncounterCounter.shortLabel();
            encounterBadge.setVisibility(count.isEmpty()?View.GONE:View.VISIBLE);
            if(!count.isEmpty()){setTextIfChanged(encounterBadge,count);encounterBadge.setContentDescription(EncounterCounter.label());}}
        if(shinyMark!=null){boolean mark=ShinyScanner.targetVisible()&&!settings.isVisible();shinyMark.setVisibility(mark?View.VISIBLE:View.GONE);
            if(mark){// The point is the top of the Pokémon; the symbol sits just above it.
            shinyMark.setX(ShinyScanner.targetX()*parent.getWidth()-dp(16));shinyMark.setY(ShinyScanner.targetY()*parent.getHeight()-dp(34));}}
        {String[] found=ShinyScanner.takePopup();if(found!=null&&!closed&&!activity.isFinishing()&&!activity.isDestroyed()){
            if(shinyPopup!=null&&shinyPopup.isShowing())shinyPopup.dismiss();
            AlertDialog.Builder popup=new AlertDialog.Builder(MapTheme.wrap(activity)).setTitle(found[0]).setMessage(found[1]);
            // A found shiny: Catch it throws for you, Dismiss lets it go, Close keeps it (the badge reopens this).
            if("found".equals(found[2]))popup.setPositiveButton("Capturer",(d,w)->{ShinyScanner.requestCatch();refresh();}).setNeutralButton("Ignorer",(d,w)->{ShinyScanner.dismissFound();refresh();}).setNegativeButton("Fermer",null);
            else popup.setPositiveButton("OK",null);
            shinyPopup=popup.create();
            final AlertDialog shown=shinyPopup;shown.setCanceledOnTouchOutside(false);DialogLifecycle.bind(activity,shown,null);shown.setOnShowListener(d->MapTheme.styleButtons(shown));shown.show();}}
        if(shinyBadge!=null){String found=settings.isVisible()||(quickMenu!=null&&quickMenu.isShowing())?"":ShinyScanner.foundLabel();
            shinyBadge.setVisibility(found.isEmpty()?View.GONE:View.VISIBLE);
            if(!found.isEmpty()){setTextIfChanged(shinyBadge,found);shinyBadge.setContentDescription(found+". Touche pour les options.");if(shinyDrag!=null)shinyDrag.refreshBounds();}}
        if(shundoOverlay!=null){shundoOverlay.refresh(!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing()));if(shundoDrag!=null)shundoDrag.refreshBounds();}
        if(gymAutomationStatus!=null){gymAutomationStatus.setVisibility(gymFeedOverlay.getVisibility());setTextIfChanged(gymAutomationStatus,GymAutomationSettings.overlayStatus());}
        if(vgpToggle!=null){vgpToggle.setVisibility(Control.vgpOverlayVisible()&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);vgpToggle.setContentDescription("Virtual Go Plus "+(Control.vgpEnabled()?"on; tap to turn off":"désactivé ; touche pour activer")+". "+Control.vgpStatus());vgpToggle.invalidate();}

        // Pair the status line with the toggle so the cooldown shutoff reason is visible even when VGP is off.
        if(buddyEmotionStatus!=null){buddyEmotionStatus.setVisibility(BuddyEmotionSettings.overlayEnabled()&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);setTextIfChanged(buddyEmotionStatus,BuddyEmotionSettings.status());}
        if(catchChance!=null){catchChance.setVisibility(CatchChance.visible()&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);setTextIfChanged(catchChance,CatchChance.text());}
        if(automationStatus!=null){automationStatus.setVisibility((OneShotAutomation.overlayVisible()||AutoRaid.running())&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);setTextIfChanged(automationStatus,AutoRaid.running()?"Auto-Raid | "+AutoRaid.status():OneShotAutomation.overlayStatus());}
        if(vgpActivity!=null){vgpActivity.setVisibility(Control.vgpOverlayVisible()&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);setTextIfChanged(vgpActivity,Control.vgpStatus());}
        if(mapCatchBadge!=null)mapCatchBadge.refresh(!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())&&!Control.catchScreenVisible());
        if(vgpMarker!=null){boolean show=Control.vgpTargetVisible()&&!settings.isVisible();vgpMarker.setVisibility(show?View.VISIBLE:View.GONE);if(show){vgpMarker.setX(Control.vgpTargetX()*parent.getWidth()-dp(19)*vgpMarker.getScaleX());vgpMarker.setY(Control.vgpTargetY()*parent.getHeight()-dp(36)-dp(46)*vgpMarker.getScaleY());vgpMarker.invalidate();}}
        if(transferToggle!=null){transferToggle.setVisibility(Control.transferOverlayVisible()&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing())?View.VISIBLE:View.GONE);transferToggle.setContentDescription(transferToggleDescription());transferToggle.invalidate();
            if(transferLabel!=null){transferLabel.setVisibility(transferToggle.getVisibility());setTextIfChanged(transferLabel,transferShort());}}

        String routeReward=Control.takeGameRoutesRewardNotice();
        if(!routeReward.isEmpty()&&!overlaysHidden)Toast.makeText(activity,routeReward,Toast.LENGTH_LONG).show();
        if(gameRoutesOverlay!=null)gameRoutesOverlay.refresh(!settings.isVisible());
        if(cooldownTimer!=null){cooldownTimer.setVisibility(Control.cooldownOverlay()?View.VISIBLE:View.GONE);setTextIfChanged(cooldownTimer,Cooldown.label());int cooldownColor=Cooldown.remaining()>0?SettingsUi.warn():accent();if(cooldownTimer.getCurrentTextColor()!=cooldownColor)cooldownTimer.setTextColor(cooldownColor);}
        // The view combines this Pad-side gate with Control.localTimeOverlay()/desiredEnabled() and its own throttle;
        // a false gate (settings open, quick menu up, or overlays hidden) stops its clock work entirely.
        if(localTime!=null){localTime.refresh(!overlaysHidden&&!settings.isVisible()&&!(quickMenu!=null&&quickMenu.isShowing()));}
        syncDock();
        if (lastEnabled != Control.engine.enabled) { lastEnabled = Control.engine.enabled; stick.invalidate(); }
        // The launcher's status dot shows whether spoofing is on; redraw it only when that changes.
        if (launcher != null && launcherSpoofing != Control.desiredEnabled()) { launcherSpoofing = Control.desiredEnabled(); launcher.invalidate(); }
        if(encounterBerry!=null)encounterBerry.refresh();
        if(quickMenu!=null)quickMenu.onRefresh();
        // The hidden panel is GONE; openSettings() refreshes it immediately after show().
        if(settings.isVisible())settings.refresh();
    }
    /** TextView.setText requests a layout pass even for identical text; this tick runs every 100 ms. */
    private static void setTextIfChanged(TextView view,CharSequence value){
        if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed) return;
            Control.checkLease();
            long now = SystemClock.elapsedRealtime();
            stick.applyMotion(now);
            boolean wasWalking = Control.engine.walking;
            MotionEngine.RouteProgress before=Control.engine.routeProgress();
            Control.stepMotion((now-lastTick)/1000.0,stick.pointer>=0); lastTick = now;
            Control.tickGameRoutes();
            MotionEngine.RouteProgress after=Control.engine.routeProgress();
            if (before.total>1 && after.total==before.total && after.visited>before.visited)
                Control.event("route-progress", "visited=" + after.visited + "/" + after.total);
            if (wasWalking && !Control.engine.walking && Control.engine.enabled) {
                Control.event(before.total>1 ? "route-completed" : "walk-arrived", String.format(Locale.US,"lat=%.7f lon=%.7f; holding destination",Control.engine.latitude,Control.engine.longitude));
                Control.saveState();
            }
            Control.checkpoint(now);
            refresh();
            if (now-lastPositionCheck >= 5000 && Control.engine.enabled) {
                lastPositionCheck = now;
                // Sample movement every five seconds, stationary once a minute; preserve room for failure evidence.
                String where = String.format(Locale.US, "lat=%.7f lon=%.7f speed=%.3f walking=%s idleWalking=%s", Control.engine.latitude, Control.engine.longitude, Control.engine.metersPerSecond(), Control.engine.walking, Control.engine.idleWalkingActive());
                if (!where.equals(lastPosition) || now-lastLog >= 60000) {
                    lastLog = now; lastPosition = where;
                    Control.event("position", where + String.format(Locale.US, " reads=%d/%d", Control.latitudeReads, Control.longitudeReads));
                }
            }
            handler.postDelayed(this, 100);
        }
    };
    private EditText input(String value, String hint) {
        EditText e = new EditText(dialogContext()); e.setSingleLine(true); e.setHint(hint); e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED); return e;
    }
    private void edit() {
        if (Control.engine.walking) Control.event("walk-cancelled", "Coordinates editor opened");
        stick.release(); refresh();
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24),dp(8),dp(24),dp(8));
        EditText lat = input(Double.toString(Control.engine.latitude), "Latitude (-90 à 90)");
        EditText lon = input(Double.toString(Control.engine.longitude), "Longitude (-180 à 180)");
        EditText speed = input(Double.toString(Control.engine.speedKmh), "Vitesse (0,5–30 km/h)");
        for (EditText field : new EditText[]{lat, lon, speed}) form.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(dialogContext()).setTitle("Coordonnées & vitesse").setView(form).setNegativeButton("Annuler",null).setPositiveButton("Appliquer",null).create();
        final AlertDialog[] confirmation = {null};
        DialogLifecycle.bind(activity,dialog,()->{if(confirmation[0]!=null)confirmation[0].dismiss();});
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                // Validate all fields before committing any state.
                MotionEngine check = new MotionEngine(); check.position(Double.parseDouble(lat.getText().toString().trim()), Double.parseDouble(lon.getText().toString().trim())); check.speed(Double.parseDouble(speed.getText().toString().trim()));
                confirmation[0]=TeleportDialog.confirm(activity,check.latitude,check.longitude,(latitude,longitude)->{
                    if(!quickTeleport(latitude,longitude))return false;
                    Control.engine.speed(check.speedKmh); Control.saveState();
                    Control.event("coordinates-set", latitude + "," + longitude + " speed=" + check.speedKmh);
                    refresh(); dialog.dismiss(); return true;
                });
            } catch (IllegalArgumentException error) { speed.setError("Entre des coordonnées et une vitesse valides (0,5–30)."); }
        })); dialog.show();styleTree(form);
    }
    /** "Hide overlay": confirm first, naming the three-finger tap that shows the overlays again. */
    void confirmHideOverlays() {
        if(closed||overlaysHidden||activity.isFinishing()||activity.isDestroyed()||hideOverlaysDialog!=null)return;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext())
            .setTitle("Masquer l'overlay ?")
            .setMessage("Tous les boutons, barres d'état, messages et textes d'IV de PGRazor seront masqués pour que le jeu ait l'air d'origine. Pour les réafficher, touche l'écran avec trois doigts en même temps.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Masquer",(d,w)->setOverlaysHidden(true)).create();
        hideOverlaysDialog=dialog;
        DialogLifecycle.bind(activity,dialog,()->{if(hideOverlaysDialog==dialog)hideOverlaysDialog=null;});
        dialog.setOnShowListener(d->MapTheme.styleButtons(dialog));
        dialog.show();
    }
    private void setOverlaysHidden(boolean hidden){
        if(closed||overlaysHidden==hidden)return;
        overlaysHidden=hidden;Control.setOverlaysHidden(hidden);
        if(hidden){stick.release(true);if(quickMenu!=null&&quickMenu.isShowing())quickMenu.hide();}
        overlays.setVisibility(hidden?View.GONE:View.VISIBLE);
        Control.event("overlays",hidden?"hidden":"shown");
        if(!hidden){Toast.makeText(activity,"Overlay PGRazor affiché",Toast.LENGTH_SHORT).show();refresh();}
    }
    /**
     * Sees every finger that touches down anywhere on screen and never takes the touch, so the game
     * and the overlays behave as before. The decor view splits multi-finger touches, so each finger
     * arrives here as its own down event. While the overlay is hidden, three fingers landing within
     * 250 ms at least 24dp apart show it again; one finger tapping the same spot quickly does not.
     */
    private final class ThreeFingerWatcher extends View {
        private final long[] times=new long[3];
        private final float[] xs=new float[3],ys=new float[3];
        private int next;
        ThreeFingerWatcher(Context context){super(context);setClickable(false);setFocusable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            int action=e.getActionMasked();
            if(!overlaysHidden||(action!=MotionEvent.ACTION_DOWN&&action!=MotionEvent.ACTION_POINTER_DOWN))return false;
            if(e.getPointerCount()>=3){unhide();return false;}
            int index=e.getActionIndex();
            times[next]=e.getEventTime();xs[next]=e.getX(index);ys[next]=e.getY(index);next=(next+1)%3;
            long newest=e.getEventTime();float apart=dp(24);
            boolean together=true;
            for(int i=0;i<3&&together;i++){
                together=times[i]!=0&&newest-times[i]<=250;
                for(int j=i+1;j<3&&together;j++)together=Math.hypot(xs[i]-xs[j],ys[i]-ys[j])>=apart;
            }
            if(together)unhide();
            return false;
        }
        private void unhide(){java.util.Arrays.fill(times,0);post(()->setOverlaysHidden(false));}
    }
    /** A ground tap must never silently replace a planned route. */
    void confirmStopRoute() {
        if(closed||activity.isFinishing()||activity.isDestroyed()||stopRouteDialog!=null)return;
        final long token=Control.engine.activeRouteToken();if(token==0)return;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext())
            .setTitle("Arrêter la route ?")
            .setMessage("Une route ou l'Autowalk est en cours. Tu veux l'arrêter ?")
            .setNegativeButton("Continuer à marcher",null)
            .setPositiveButton("Arrêter la route",(d,w)->{
                if(!closed&&Control.engine.stopRoute(token)){
                    Control.saveState();Control.event("route-stopped","Confirmed after map tap");refresh();
                }
            }).create();
        stopRouteDialog=dialog;
        DialogLifecycle.bind(activity,dialog,()->{if(stopRouteDialog==dialog)stopRouteDialog=null;});
        dialog.setOnShowListener(d->MapTheme.styleButtons(dialog));
        dialog.show();
    }

    /** Shared World map handler for both the settings list and the quick menu Map row. */
    private void openWorldMap() {
        stick.release(true);
        Control.event("map-open", "Browsing does not apply a destination; current walk=" + Control.engine.walking);
        WorldMapDialog.show(activity, Control.engine.latitude, Control.engine.longitude, (lat, lon) -> {
            if (!Control.canEnable()) { Toast.makeText(activity,"L'adaptateur de position n'est pas prêt",Toast.LENGTH_SHORT).show(); return false; }
            try {
                stick.release(); Control.teleport(lat, lon);
                Control.event("map-teleport", String.format(Locale.US,"lat=%.7f lon=%.7f",lat,lon));
                refresh(); return true;
            } catch (IllegalArgumentException error) { Toast.makeText(activity,"Choisis des coordonnées valides",Toast.LENGTH_SHORT).show(); return false; }
        }, (lat, lon) -> {
            if (!Control.canEnable()) { Toast.makeText(activity,"L'adaptateur de position n'est pas prêt",Toast.LENGTH_SHORT).show(); return false; }
            try {
                stick.release(); Control.walkTo(lat, lon);
                Control.event("walk-started", String.format(Locale.US,"targetLat=%.7f targetLon=%.7f speedKmh=%.2f",lat,lon,Control.engine.speedKmh));
                if (!Control.engine.walking) Control.event("walk-arrived", "Already at selected destination");
                refresh(); return true;
            } catch (IllegalArgumentException error) { Toast.makeText(activity,"Choisis des coordonnées valides",Toast.LENGTH_SHORT).show(); return false; }
        }, (waypoints, loop) -> {
            if (!Control.canEnable()) { Toast.makeText(activity,"L'adaptateur de position n'est pas prêt",Toast.LENGTH_SHORT).show(); return false; }
            try {
                stick.release(true); Control.walkRoute(waypoints, loop);
                MotionEngine.RouteProgress progress=Control.engine.routeProgress();
                Control.event("route-started", String.format(Locale.US,"stops=%d visited=%d remainingMeters=%.2f speedKmh=%.2f loop=%s",progress.total,progress.visited,progress.remainingMeters,Control.engine.speedKmh,loop));
                refresh(); return true;
            } catch (IllegalArgumentException error) { Toast.makeText(activity,error.getMessage(),Toast.LENGTH_SHORT).show(); return false; }
        });
    }
    /** Opens the existing settings panel full screen; the game runtime is left untouched. */
    private void openSettings(int page) {
        stick.release(true);
        settings.showPage(page); settings.applyInsets(parent.getRootWindowInsets());
        Control.event("settings-open", "Full-screen panel; runtime unaffected");
        refresh();
    }
    /** Shared quick teleport for the Favorites and Teleport modals; validates readiness. */
    private boolean quickTeleport(double latitude, double longitude) {
        if (!Control.canEnable()) { Toast.makeText(activity,"L'adaptateur de position n'est pas prêt",Toast.LENGTH_SHORT).show(); return false; }
        try {
            stick.release(); Control.teleport(latitude, longitude);
            Control.event("quick-teleport", String.format(Locale.US,"lat=%.7f lon=%.7f",latitude,longitude));
            refresh(); return true;
        } catch (IllegalArgumentException error) { Toast.makeText(activity,"Entre des coordonnées valides",Toast.LENGTH_SHORT).show(); return false; }
    }
    void confirmMapTeleport(double latitude,double longitude,int mode,int epoch) {
        TeleportDialog.confirm(activity,latitude,longitude,(lat,lon)->{
            if(closed||!Control.mapTapAllowed()||mode!=Control.mapTapMode()||epoch!=Control.mapTapEpoch()||Control.engine.activeRouteToken()!=0) {
                Toast.makeText(activity,"Réglages du tap sur la carte modifiés ; touche à nouveau la carte.",Toast.LENGTH_SHORT).show();return true;
            }
            if(!quickTeleport(lat,lon))return false;
            Control.event("map-tap-applied",String.format(Locale.US,"mode=%d lat=%.7f lon=%.7f",mode,lat,lon));return true;
        });
    }
    private boolean quickWalk(double latitude,double longitude) {
        if(!Control.canEnable()){Toast.makeText(activity,"L'adaptateur de position n'est pas prêt",Toast.LENGTH_SHORT).show();return false;}
        try {
            stick.release();Control.walkTo(latitude,longitude);
            Control.event("quick-walk",String.format(Locale.US,"lat=%.7f lon=%.7f",latitude,longitude));
            refresh();return true;
        } catch(IllegalArgumentException error){Toast.makeText(activity,"Choisis des coordonnées valides",Toast.LENGTH_SHORT).show();return false;}
    }
    public void close() {
        if (closed) return;
        closed = true;if(stopRouteDialog!=null)stopRouteDialog.dismiss(); stick.release(true); Control.suspendForBackground(); handler.removeCallbacks(tick);
        if(quickMenu!=null){quickMenu.destroy();quickMenu=null;}
        if(dockDrag!=null){dockDrag.detach();dockDrag=null;}if(dock!=null)dock.setDrag(null);
        if(localTime!=null){localTime.close();localTime=null;}
        automationStatus=null;
        if(catchChanceDrag!=null){catchChanceDrag.detach();catchChanceDrag=null;}if(catchChance!=null){overlays.removeView(catchChance);catchChance=null;}
        EncounterCounter.flush();encounterBadge=null;
        if(shinyPopup!=null){shinyPopup.dismiss();shinyPopup=null;}if(shinyMark!=null){overlays.removeView(shinyMark);shinyMark=null;}
        if(shinyDrag!=null){shinyDrag.detach();shinyDrag=null;}if(shinyBadge!=null){overlays.removeView(shinyBadge);shinyBadge=null;}
        if(shundoDrag!=null){shundoDrag.detach();shundoDrag=null;}if(shundoOverlay!=null){shundoOverlay.close();overlays.removeView(shundoOverlay);shundoOverlay=null;}
        gymAutomationStatus=null;
        if(gymFeedOverlay!=null){gymFeedOverlay.close();gymFeedOverlay=null;}
        settings.close();if(hideOverlaysDialog!=null)hideOverlaysDialog.dismiss();
        parent.removeView(settings.view());if(dock!=null){overlays.removeView(dock);dock=null;}overlays.removeView(joystickOverlay);if(encounterBerry!=null){encounterBerry.close();overlays.removeView(encounterBerry);}if(vgpMarker!=null)overlays.removeView(vgpMarker);if(mapCatchBadge!=null){overlays.removeView(mapCatchBadge);mapCatchBadge=null;}if(gameRoutesOverlay!=null)gameRoutesOverlay.close();
        parent.removeView(overlays);if(unhideWatcher!=null)parent.removeView(unhideWatcher);
        if(blackScreen!=null){blackScreen.setOnTouchListener(null);parent.removeView(blackScreen);blackScreen=null;blackHint=null;}
        ShundoSniper.setDark(false);
        ScreenPower.turnOn(activity);  // leaving the game always brings the screen back; it stays on when the game returns
        settings.view().setOnApplyWindowInsetsListener(null);
        Control.event("panel-closed", "Runtime suspended on activity change; desiredEnabled=" + Control.desiredEnabled() + "; movement stopped");
    }
    private final class Stick extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x, y;
        int pointer = -1;
        long cameraGesture=-1;
        boolean gestureCamera;
        Stick(Context c) { super(c); setContentDescription("Joystick de position : glisse pour bouger, relâche pour t'arrêter"); }
        void release() { release(false); }
        void release(boolean preserveWalk) { x=y=0; pointer=-1; cameraGesture=-1; if (!preserveWalk || !Control.engine.walking) Control.engine.stop(); Control.saveState(); invalidate(); }
        void applyMotion(long now) {
            if (pointer<0) return;
            if (!Control.engine.enabled || !Control.canEnable() || gestureCamera!=Control.cameraRelative()) { release(true); return; }
            boolean wasWalking=Control.engine.walking;
            if (gestureCamera) {
                if (!Control.applyCameraInput(cameraGesture,x,-y,now)) {
                    release(true);
                    Control.event("joystick-released", "Camera unavailable or changed; a new drag is required");
                    return;
                }
            } else Control.engine.input(x,-y);
            if (wasWalking) Control.event("walk-cancelled", "Manual joystick input");
        }
        protected void onDraw(Canvas canvas) {
            float cx=getWidth()/2f, cy=getHeight()/2f, radius=Math.min(cx,cy)-dp(12);
            // A translucent instrument disc: the game stays visible through it, the chrome stays legible.
            paint.setStyle(Paint.Style.FILL);paint.setColor((surface()&0x00ffffff)|0xcc000000);canvas.drawCircle(cx,cy,radius,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(1)));paint.setColor(SettingsUi.border());canvas.drawCircle(cx,cy,radius,paint);
            paint.setColor((muted()&0x00ffffff)|0x99000000);
            canvas.drawLine(cx-radius+dp(10),cy,cx+radius-dp(10),cy,paint); canvas.drawLine(cx,cy-radius+dp(10),cx,cy+radius-dp(10),paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Control.engine.enabled?accent():SettingsUi.faint());
            canvas.drawCircle(cx+x*radius*.65f,cy+y*radius*.65f,dp(20),paint);
        }
        public boolean onTouchEvent(MotionEvent e) {
            int action=e.getActionMasked();
            if (action==MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                // A deliberate manual takeover cancels even when a fresh camera
                // heading is unavailable. Passive camera loss preserves a walk.
                if (Control.engine.walking) {
                    Control.engine.stop(); Control.saveState();
                    Control.event("walk-cancelled", "Manual joystick touch");
                }
                pointer=e.getPointerId(0); gestureCamera=Control.cameraRelative();
                cameraGesture=gestureCamera ? Control.beginCameraGesture() : -1;
            }
            if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL ||
                (action==MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.getActionIndex())==pointer)) {
                getParent().requestDisallowInterceptTouchEvent(false);
                release(pointer<0); Control.event("joystick", "released"); return true;
            }
            int index=e.findPointerIndex(pointer); if(index<0) return true;
            float radius=Math.min(getWidth(),getHeight())/2f-dp(12);
            x=(e.getX(index)-getWidth()/2f)/radius; y=(e.getY(index)-getHeight()/2f)/radius;
            float length=(float)Math.max(1,Math.hypot(x,y)); x/=length; y/=length;
            if(Math.hypot(x,y)<0.08) x=y=0;
            applyMotion(SystemClock.elapsedRealtime()); invalidate(); return true;
        }
    }
}
