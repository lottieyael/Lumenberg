package dev.lumenberg.glass;

import android.app.*;
import android.app.role.RoleManager;
import android.appwidget.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A completely local home screen. Widgets are real AppWidgetHost views. */
public final class LauncherActivity extends Activity {
    private static final int WHITE = 0xFFF5F1FF, MUTED = 0xFFDDD5EB;
    private static final int BIND_WIDGET = 41, CONFIGURE_WIDGET = 42, WALLPAPER = 43, HOME_ROLE = 44;
    private static final int HOST_ID = 281;
    private android.content.SharedPreferences prefs;
    private GlassBackdrop backdrop;
    private FrameLayout root, home, overlay;
    private GlassSurface sheet;
    private LinearLayout tileContainer;
    private AppWidgetHost host;
    private AppWidgetManager widgetManager;
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private final ArrayList<App> apps = new ArrayList<>();
    private final ArrayList<Runnable> clockUpdates = new ArrayList<>();
    private final HashMap<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private final String[] pins = new String[4];
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LauncherApps launcherApps;
    private boolean dockLeft, editing, listening, appScanRunning, destroyed;
    private int topInset, bottomInset, lastWidth, battery = -1;
    private int pendingId = -1, choosingSlot = -1;
    private ComponentName pendingProvider;
    private String overlayKind = "", appQuery = "";
    private LinearLayout appGrid;

    private static final class App {
        final String label, key; final ComponentName component; final Drawable icon;
        App(LauncherActivityInfo info) {
            label = info.getLabel().toString(); component = info.getComponentName();
            key = component.flattenToString(); icon = info.getIcon(0);
        }
    }
    private static final class Tile {
        String key, kind; int id, span; boolean square;
        Tile(String kind, int id, int span, boolean square) {
            this.kind=kind; this.id=id; this.span=span; this.square=square;
            key = kind.equals("android") ? "android:"+id : kind+":"+UUID.randomUUID();
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("key",key).put("kind",kind).put("id",id).put("span",span).put("square",square);
        }
        static Tile read(JSONObject o) {
            Tile t=new Tile(o.optString("kind","clock"),o.optInt("id",-1),o.optInt("span",1)==1?1:2,o.optBoolean("square",true));
            t.key=o.optString("key",t.key); return t;
        }
    }

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                battery=level<0?-1:Math.round(level*100f/Math.max(1,scale));
            }
            for (Runnable update : new ArrayList<>(clockUpdates)) update.run();
        }
    };
    private final LauncherApps.Callback packageWatcher = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String p, UserHandle u) { scanApps(); }
        @Override public void onPackageRemoved(String p, UserHandle u) { scanApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { scanApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) { scanApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) { scanApps(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("glass",MODE_PRIVATE);
        dockLeft=prefs.getBoolean("dock_left",false);
        pendingId=prefs.getInt("pending_widget",-1);
        pendingProvider=ComponentName.unflattenFromString(prefs.getString("pending_provider",""));
        launcherApps=getSystemService(LauncherApps.class);
        widgetManager=AppWidgetManager.getInstance(this); host=new AppWidgetHost(this,HOST_ID);
        loadState();
        if (Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT>=29) getWindow().setNavigationBarContrastEnforced(false);
        root=new FrameLayout(this); backdrop=new GlassBackdrop(this);
        root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        home=new FrameLayout(this); root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)-> {
            int t,b,l,r;
            if (Build.VERSION.SDK_INT>=30) {
                Insets system=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                t=system.top; b=system.bottom; l=system.left; r=system.right;
            } else { t=insets.getSystemWindowInsetTop(); b=insets.getSystemWindowInsetBottom(); l=insets.getSystemWindowInsetLeft(); r=insets.getSystemWindowInsetRight(); }
            if (t!=topInset||b!=bottomInset||home.getPaddingLeft()!=l||home.getPaddingRight()!=r) {
                topInset=t; bottomInset=b; home.setPadding(l,t,r,b); ui.post(this::renderHome);
            }
            if (sheet!=null) {
                int keyboard=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.ime()).bottom:b;
                FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)sheet.getLayoutParams();
                lp.topMargin=topInset+dp(86);lp.bottomMargin=Math.max(bottomInset,keyboard)+dp(20);sheet.setLayoutParams(lp);
            }
            return insets;
        });
        home.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)-> {
            if (r-l!=lastWidth) { lastWidth=r-l; ui.post(this::renderHome); }
        });
        home.setOnLongClickListener(v->{ showSettings(); return true; });
        root.requestApplyInsets();
        launcherApps.registerCallback(packageWatcher,ui);
        IntentFilter clockFilter=new IntentFilter();
        clockFilter.addAction(Intent.ACTION_TIME_TICK); clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
        clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED); clockFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(clockReceiver,clockFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(clockReceiver,clockFilter);
        if (Build.VERSION.SDK_INT>=33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,this::goBack);
        loadWallpaper(); scanApps(); renderHome();
        if (saved!=null&&"apps".equals(saved.getString("overlay"))) ui.post(()->showApps(-1));
    }

    @Override protected void onStart() { super.onStart(); try { host.startListening(); listening=true; } catch (RuntimeException e) { toast("Widgets are temporarily unavailable."); } }
    @Override protected void onStop() { if (listening) { host.stopListening(); listening=false; } super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        if (home!=null) {
            reconcileWidgets(); for (Runnable update : new ArrayList<>(clockUpdates)) update.run();
            if (apps.isEmpty()) scanApps();
        }
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); renderHome(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); closeOverlay(); if (editing) { editing=false; renderHome(); } }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("overlay",overlayKind); }
    @Override public void onBackPressed() { goBack(); }
    private void goBack() { if (overlay!=null) closeOverlay(); else if (editing) { editing=false; renderHome(); } }
    @Override protected void onDestroy() {
        destroyed=true; ui.removeCallbacksAndMessages(null); worker.shutdownNow();
        launcherApps.unregisterCallback(packageWatcher); unregisterReceiver(clockReceiver); super.onDestroy();
    }

    private void loadState() {
        try {
            JSONArray items=new JSONArray(prefs.getString("tiles","[]"));
            for (int i=0;i<items.length();i++) tiles.add(Tile.read(items.getJSONObject(i)));
            JSONArray dock=new JSONArray(prefs.getString("pins","[]"));
            for (int i=0;i<4;i++) pins[i]=dock.optString(i,"");
        } catch (JSONException e) { tiles.clear(); }
        if (!prefs.contains("tiles")) {
            tiles.add(new Tile("clock",-1,2,true)); tiles.add(new Tile("date",-1,1,true)); tiles.add(new Tile("battery",-1,1,true)); saveTiles();
        }
    }
    private void saveTiles() {
        JSONArray json=new JSONArray();
        try { for (Tile t : tiles) json.put(t.json()); } catch (JSONException e) { return; }
        prefs.edit().putString("tiles",json.toString()).apply();
    }
    private void savePins() {
        JSONArray json=new JSONArray(); for (String p:pins) json.put(p==null?"":p);
        prefs.edit().putString("pins",json.toString()).apply();
    }
    private void scanApps() {
        if (appScanRunning||destroyed) return; appScanRunning=true;
        worker.execute(()-> {
            ArrayList<App> found=new ArrayList<>();
            try {
                for (LauncherActivityInfo info : launcherApps.getActivityList(null,android.os.Process.myUserHandle())) {
                    if (!info.getComponentName().getPackageName().equals(getPackageName())) {
                        try { found.add(new App(info)); } catch (RuntimeException ignored) { }
                    }
                }
                found.sort(Comparator.comparing(a->a.label.toLowerCase(Locale.getDefault())));
            } catch (RuntimeException ignored) { }
            ui.post(()-> {
                if (destroyed) return; appScanRunning=false; apps.clear(); apps.addAll(found);
                if (!prefs.contains("pins")) defaultPins(); renderHome();
                if ("apps".equals(overlayKind)) renderAppGrid();
            });
        });
    }
    private void defaultPins() {
        Intent[] intents={new Intent(Intent.ACTION_DIAL),new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)};
        for (int i=0;i<4;i++) {
            ResolveInfo resolved=getPackageManager().resolveActivity(intents[i],PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved!=null) for (App a:apps) if (a.component.getPackageName().equals(resolved.activityInfo.packageName)) { pins[i]=a.key; break; }
            if (pins[i]==null||pins[i].isEmpty()) {
                String wanted=new String[]{"phone","messages","chrome","camera"}[i];
                for (App a:apps) if (a.label.toLowerCase(Locale.ROOT).contains(wanted)) { pins[i]=a.key; break; }
            }
        }
        if (!apps.isEmpty()) savePins();
    }
    private App findApp(String key) { for (App a:apps) if (a.key.equals(key)) return a; return null; }
    private void launch(App app) {
        try {
            launcherApps.startMainActivity(app.component,android.os.Process.myUserHandle(),null,null); closeOverlay();
        } catch (RuntimeException e) { toast("This app is unavailable. Long-press its dock slot to replace it."); scanApps(); }
    }

    private void renderHome() {
        if (home==null||destroyed) return;
        home.removeAllViews(); clockUpdates.clear();
        int available=home.getWidth()-home.getPaddingLeft()-home.getPaddingRight()-dp(108);
        if (available<=0) available=getResources().getDisplayMetrics().widthPixels-dp(108);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=text("space",26,"sans-serif-medium"); header.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        header.addView(symbolButton(editing?"check":"plus",editing?"Finish arranging":"Add widget",()-> {
            if (editing) { editing=false; renderHome(); } else showAddWidget();
        }),new LinearLayout.LayoutParams(dp(44),dp(44)));
        header.addView(symbolButton("more","Customize launcher",this::showSettings),new LinearLayout.LayoutParams(dp(44),dp(44)));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,dp(64)); hp.leftMargin=dp(22); hp.rightMargin=dp(18); hp.topMargin=dp(12);
        home.addView(header,hp);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false); scroll.setClipToPadding(false);
        tileContainer=new LinearLayout(this); tileContainer.setOrientation(LinearLayout.VERTICAL);
        tileContainer.setPadding(0,dp(4),0,dp(24)); scroll.addView(tileContainer,new ScrollView.LayoutParams(-1,-2));
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,-1); sp.topMargin=dp(90); sp.bottomMargin=dp(80);
        sp.leftMargin=dp(dockLeft?88:20); sp.rightMargin=dp(dockLeft?20:88);
        home.addView(scroll,sp);
        renderTiles(available);
        GlassSurface dock=new GlassSurface(this,backdrop,31,false);
        LinearLayout slots=new LinearLayout(this); slots.setOrientation(LinearLayout.VERTICAL); slots.setGravity(Gravity.CENTER); slots.setPadding(dp(6),dp(8),dp(6),dp(8));
        dock.addView(slots,new FrameLayout.LayoutParams(-1,-2));
        for (int i=0;i<4;i++) {
            final int slot=i; App app=findApp(pins[i]);
            FrameLayout button=new FrameLayout(this); button.setContentDescription(app==null?"Choose dock app "+(i+1):app.label);
            button.setBackground(pressBackground(20));
            if (app!=null) {
                ImageView icon=new ImageView(this); icon.setImageDrawable(app.icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(35),dp(35),Gravity.CENTER); button.addView(icon,ip);
            } else button.addView(new SymbolView(this,"plus"),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
            button.setOnClickListener(v->{ App current=findApp(pins[slot]); if (current==null) showApps(slot); else launch(current); });
            button.setOnLongClickListener(v->{ showApps(slot); return true; }); animatePress(button);
            slots.addView(button,new LinearLayout.LayoutParams(dp(48),dp(55)));
        }
        View divider=new View(this); divider.setBackgroundColor(0x3DFFFFFF); LinearLayout.LayoutParams sep=new LinearLayout.LayoutParams(dp(28),dp(1)); sep.topMargin=dp(8); sep.bottomMargin=dp(8); slots.addView(divider,sep);
        View all=symbolButton("apps","All apps",()->showApps(-1)); all.setBackground(pressBackground(19));
        slots.addView(all,new LinearLayout.LayoutParams(dp(48),dp(52)));
        FrameLayout.LayoutParams dp=new FrameLayout.LayoutParams(dp(60),-2,Gravity.BOTTOM|(dockLeft?Gravity.LEFT:Gravity.RIGHT));
        dp.leftMargin=dp(14); dp.rightMargin=dp(14); dp.bottomMargin=dp(28); home.addView(dock,dp);
        if (tiles.isEmpty()) {
            TextView empty=text("Make this space yours.\nAdd a clock, a note, or a widget from your apps.",17,"sans-serif");
            empty.setLineSpacing(dp(6),1); empty.setTextColor(MUTED); empty.setPadding(dp(4),dp(28),dp(4),dp(28)); tileContainer.addView(empty);
        }
        if (editing) {
            TextView help=text("Drag a tile onto another to reorder.",12,"sans-serif"); help.setTextColor(MUTED); help.setPadding(dp(4),dp(18),0,0); tileContainer.addView(help);
        }
        GlassSurface add=new GlassSurface(this,backdrop,26,false); LinearLayout addRow=new LinearLayout(this); addRow.setGravity(Gravity.CENTER);
        SymbolView plus=new SymbolView(this,editing?"check":"plus"); addRow.addView(plus,new LinearLayout.LayoutParams(dp(18),dp(18)));
        TextView addLabel=text(editing?"Done":"Add widget",14,"sans-serif-medium"); addLabel.setPadding(dp(8),0,0,0); addRow.addView(addLabel);
        add.addView(addRow,new FrameLayout.LayoutParams(-1,-1)); add.setContentDescription(editing?"Finish arranging widgets":"Add widget");
        add.setOnClickListener(v->{ if (editing) { editing=false; renderHome(); } else showAddWidget(); }); animatePress(add);
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(136),dp(48),Gravity.BOTTOM|(dockLeft?Gravity.RIGHT:Gravity.LEFT));
        ap.leftMargin=dp(20); ap.rightMargin=dp(20); ap.bottomMargin=dp(28); home.addView(add,ap);
    }

    private void renderTiles(int available) {
        int gap=dp(12), small=GridPlanner.smallWidth(available,gap);
        LinearLayout row=null; int used=2;
        for (Tile tile:tiles) {
            if (used+tile.span>2) { row=new LinearLayout(this); row.setGravity(Gravity.TOP); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.bottomMargin=gap; tileContainer.addView(row,rp); used=0; }
            int width=tile.span==1?small:available;
            int height=GridPlanner.height(width,tile.square,dp(tile.span==2?155:220));
            LinearLayout wrapper=new LinearLayout(this); wrapper.setOrientation(LinearLayout.VERTICAL);
            GlassSurface surface=new GlassSurface(this,backdrop,tile.span==2?29:25,false);
            surface.watchProviderLongPress(tile.kind.equals("android")&&!editing);
            wrapper.addView(surface,new LinearLayout.LayoutParams(width,height));
            if (tile.kind.equals("android")) attachWidget(surface,tile,width,height); else builtIn(surface,tile,width);
            if (editing) {
                LinearLayout controls=new LinearLayout(this); controls.setGravity(Gravity.CENTER); controls.setPadding(0,dp(4),0,0);
                controls.addView(symbolButton("resize","Resize widget",()->showTileOptions(tile)),new LinearLayout.LayoutParams(0,dp(44),1));
                controls.addView(symbolButton("up","Move widget earlier",()->moveTile(tile,-1)),new LinearLayout.LayoutParams(0,dp(44),1));
                controls.addView(symbolButton("close","Remove widget",()->removeTile(tile)),new LinearLayout.LayoutParams(0,dp(44),1));
                wrapper.addView(controls,new LinearLayout.LayoutParams(width,dp(48)));
                surface.setOnLongClickListener(v->{
                    ClipData clip=ClipData.newPlainText("widget",tile.key);
                    v.startDragAndDrop(clip,new View.DragShadowBuilder(v),tile.key,0); return true;
                });
                wrapper.setOnDragListener((v,event)-> {
                    if (event.getAction()==DragEvent.ACTION_DRAG_STARTED) return event.getLocalState() instanceof String;
                    if (event.getAction()==DragEvent.ACTION_DROP) {
                        String source=(String)event.getLocalState(); int from=-1,to=tiles.indexOf(tile);
                        for(int i=0;i<tiles.size();i++) if(tiles.get(i).key.equals(source)) from=i;
                        if(from>=0&&to>=0&&from!=to) { Tile moved=tiles.remove(from); tiles.add(to,moved); saveTiles(); ui.post(this::renderHome); }
                        return true;
                    }
                    return true;
                });
                // Capture provider touches while editing so widgets cannot open apps during a drag.
                View shield=new View(this); surface.addView(shield,new FrameLayout.LayoutParams(-1,-1));
                shield.setContentDescription("Edit "+tileLabel(tile)); shield.setOnClickListener(v->showTileOptions(tile));
                shield.setOnLongClickListener(v->surface.performLongClick());
            } else surface.setOnLongClickListener(v->{ showTileOptions(tile); return true; });
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(width,-2); if(used>0) wp.leftMargin=gap;
            row.addView(wrapper,wp); used+=tile.span;
        }
    }

    private void builtIn(GlassSurface surface, Tile tile, int width) {
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_VERTICAL);
        int padding=dp(tile.span==2?25:18); body.setPadding(padding,padding,padding,padding);
        surface.addView(body,new FrameLayout.LayoutParams(-1,-1));
        float big=tile.span==2?58:43;
        if (tile.kind.equals("clock")) {
            TextView caption=text("LOCAL TIME",11,"sans-serif-medium"); caption.setLetterSpacing(.12f); caption.setTextColor(MUTED); body.addView(caption);
            TextView value=text("",big,"sans-serif-light"); value.setIncludeFontPadding(false); value.setPadding(0,dp(10),0,dp(8));
            value.setSingleLine(true); body.addView(value);
            TextView date=text("",tile.span==2?15:12,"sans-serif"); date.setTextColor(MUTED); body.addView(date);
            Runnable update=()-> {
                String pattern=android.text.format.DateFormat.is24HourFormat(this)?"HH:mm":"h:mm";
                value.setText(format(pattern)); date.setText(format(tile.span==2?"EEEE, d MMMM":"EEE, d MMM"));
            }; clockUpdates.add(update); update.run();
            surface.setContentDescription("Clock. Tap to open alarms.");
            surface.setOnClickListener(v->openSystem(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)));
        } else if (tile.kind.equals("date")) {
            TextView month=text("",13,"sans-serif-medium"); month.setTextColor(MUTED); body.addView(month);
            TextView day=text("",big,"sans-serif-light"); day.setIncludeFontPadding(false); day.setPadding(0,dp(6),0,dp(4)); body.addView(day);
            TextView weekday=text("",13,"sans-serif"); weekday.setTextColor(MUTED); body.addView(weekday);
            Runnable update=()-> {month.setText(format("MMMM")); day.setText(format("d")); weekday.setText(format("EEEE"));}; clockUpdates.add(update); update.run();
            surface.setContentDescription("Date. Tap to open calendar.");
            surface.setOnClickListener(v->openSystem(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)));
        } else if (tile.kind.equals("battery")) {
            LinearLayout cap=new LinearLayout(this); cap.setGravity(Gravity.CENTER_VERTICAL);
            SymbolView icon=new SymbolView(this,"battery"); cap.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(18)));
            TextView label=text("Battery",13,"sans-serif-medium"); label.setTextColor(MUTED); label.setPadding(dp(6),0,0,0); cap.addView(label); body.addView(cap);
            TextView value=text("",tile.span==2?50:36,"sans-serif-light"); value.setPadding(0,dp(6),0,dp(8)); value.setIncludeFontPadding(false); body.addView(value);
            FrameLayout track=new FrameLayout(this); track.setBackground(roundBackground(0x23FFFFFF,3));
            View fill=new View(this); fill.setBackground(roundBackground(0xFFE1D5F9,3)); track.addView(fill,new FrameLayout.LayoutParams(0,-1));
            body.addView(track,new LinearLayout.LayoutParams(-1,dp(4)));
            Runnable update=()-> {
                value.setText(battery>=0?battery+"%":"—");
                int inner=width-padding*2;
                FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)fill.getLayoutParams(); lp.width=Math.max(0,Math.round(inner*Math.max(0,battery)/100f)); fill.setLayoutParams(lp);
            }; clockUpdates.add(update); update.run();
            surface.setContentDescription("Battery level"); surface.setOnClickListener(v->openSystem(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        } else if (tile.kind.equals("note")) {
            TextView label=text("Note",13,"sans-serif-medium"); label.setTextColor(MUTED); body.addView(label);
            TextView note=text(prefs.getString("note_"+tile.key,"Tap to write"),tile.span==2?22:15,"sans-serif");
            note.setPadding(0,dp(10),0,0); note.setEllipsize(TextUtils.TruncateAt.END); note.setMaxLines(tile.span==2?8:4);
            body.addView(note,new LinearLayout.LayoutParams(-1,-2));
            surface.setContentDescription("Note. Tap to edit."); surface.setOnClickListener(v->editNote(tile));
        }
    }

    private void attachWidget(GlassSurface surface, Tile tile, int width, int height) {
        AppWidgetProviderInfo info=widgetManager.getAppWidgetInfo(tile.id);
        if (info==null) {
            TextView missing=text("Widget unavailable",14,"sans-serif"); missing.setGravity(Gravity.CENTER); surface.addView(missing,new FrameLayout.LayoutParams(-1,-1)); return;
        }
        AppWidgetHostView view=widgetViews.get(tile.id);
        if (view==null) { view=host.createView(this,tile.id,info); widgetViews.put(tile.id,view); }
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        view.setPadding(dp(6),dp(6),dp(6),dp(6));
        surface.addView(view,new FrameLayout.LayoutParams(-1,-1));
        final AppWidgetHostView hostView=view;
        surface.post(()-> {
            if (surface.getParent()==null) return;
            // The host receives the exact square or rectangular footprint in dp.
            float w=surface.getWidth()/density(),h=surface.getHeight()/density();
            if(w<=0||h<=0) return;
            if (Build.VERSION.SDK_INT>=31) hostView.updateAppWidgetSize(new Bundle(),Collections.singletonList(new android.util.SizeF(w,h)));
            else hostView.updateAppWidgetSize(new Bundle(),Math.round(w),Math.round(h),Math.round(w),Math.round(h));
        });
    }

    private String tileLabel(Tile t) {
        if(t.kind.equals("android")) {
            AppWidgetProviderInfo info=widgetManager.getAppWidgetInfo(t.id);
            return info==null?"Widget":info.loadLabel(getPackageManager());
        }
        return switch(t.kind) {case "clock"->"Clock";case "date"->"Date";case "battery"->"Battery";default->"Note";};
    }
    private void showTileOptions(Tile tile) {
        LinearLayout list=beginSheet(tileLabel(tile),"widget");
        sheetAction(list,"Small square",tile.span==1&&tile.square?"Selected":"Half-width · 1:1",()->resize(tile,1,true));
        sheetAction(list,"Large square",tile.span==2&&tile.square?"Selected":"Full-width · 1:1",()->resize(tile,2,true));
        sheetAction(list,"Wide",tile.span==2&&!tile.square?"Selected":"Full-width rectangle",()->resize(tile,2,false));
        sheetAction(list,"Arrange widgets","Drag to reorder; resize or remove tiles",()->{closeOverlay();editing=true;renderHome();});
        sheetAction(list,"Remove widget","Remove from this space",()->removeTile(tile));
    }
    private void resize(Tile t,int span,boolean square) {t.span=span;t.square=square;saveTiles();closeOverlay();renderHome();}
    private void removeTile(Tile tile) {
        if(tile.kind.equals("android")) {host.deleteAppWidgetId(tile.id);widgetViews.remove(tile.id);}
        prefs.edit().remove("note_"+tile.key).apply(); tiles.remove(tile); saveTiles();closeOverlay();renderHome();
    }
    private void moveTile(Tile tile,int delta) {
        int from=tiles.indexOf(tile),to=Math.max(0,Math.min(tiles.size()-1,from+delta));
        if(from>=0&&from!=to) {tiles.remove(from);tiles.add(to,tile);saveTiles();renderHome();}
    }
    private void editNote(Tile t) {
        LinearLayout body=beginSheet("Edit note","note");
        EditText input=new EditText(this); input.setText(prefs.getString("note_"+t.key,"")); input.setHint("Write something worth keeping here");
        input.setTextColor(WHITE);input.setHintTextColor(MUTED);input.setTextSize(18);input.setGravity(Gravity.TOP);input.setMinLines(3);
        input.setMaxLines(6); input.setBackground(roundBackground(0x22FFFFFF,16));input.setPadding(dp(16),dp(14),dp(16),dp(14));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        sheetAction(body,"Save note","",()->{prefs.edit().putString("note_"+t.key,input.getText().toString()).apply();closeOverlay();renderHome();});
        input.requestFocus();input.post(()->getSystemService(InputMethodManager.class).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT));
    }

    private void showApps(int pinSlot) {
        LinearLayout body=beginSheet(pinSlot<0?"All apps":"Choose dock app "+(pinSlot+1),"apps");
        choosingSlot=pinSlot; appQuery="";
        EditText search=new EditText(this); search.setSingleLine(true);search.setTextSize(16);search.setTextColor(WHITE);search.setHintTextColor(MUTED);
        search.setHint("Find an app"); search.setPadding(dp(16),0,dp(16),0);search.setBackground(roundBackground(0x20FFFFFF,17));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        body.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        appGrid=new LinearLayout(this);appGrid.setOrientation(LinearLayout.VERTICAL);appGrid.setPadding(0,dp(16),0,0);
        body.addView(appGrid,new LinearLayout.LayoutParams(-1,-2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {appQuery=s.toString();renderAppGrid();}
            @Override public void afterTextChanged(Editable e) {}
        });
        search.setOnEditorActionListener((v,action,event)-> {
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO) {List<App> filtered=filteredApps();if(!filtered.isEmpty()) selectApp(filtered.get(0));return true;}return false;
        });
        // Browsing is the default: the keyboard opens only when the search field is tapped.
        body.setFocusableInTouchMode(true);body.requestFocus();renderAppGrid();
    }
    private List<App> filteredApps() {
        String query=appQuery.trim().toLowerCase(Locale.getDefault());ArrayList<App> result=new ArrayList<>();
        for(App a:apps) if(a.label.toLowerCase(Locale.getDefault()).contains(query)) result.add(a);
        return result;
    }
    private void renderAppGrid() {
        if(appGrid==null||!"apps".equals(overlayKind)) return;
        appGrid.removeAllViews();List<App> filtered=filteredApps();
        if(filtered.isEmpty()) {TextView empty=text(appScanRunning?"Loading apps…":"No matching apps",16,"sans-serif");empty.setPadding(dp(8),dp(20),dp(8),dp(20));appGrid.addView(empty);return;}
        LinearLayout row=null;int count=0;
        for(App app:filtered) {
            if(count%3==0) {row=new LinearLayout(this);row.setGravity(Gravity.TOP);appGrid.addView(row,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(4),dp(12),dp(4),dp(12));
            cell.setBackground(pressBackground(20));cell.setContentDescription(app.label);
            ImageView icon=new ImageView(this);icon.setImageDrawable(app.icon);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon,new LinearLayout.LayoutParams(dp(45),dp(45)));
            TextView label=text(app.label,12,"sans-serif");label.setGravity(Gravity.CENTER);label.setPadding(0,dp(9),0,0);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(label,new LinearLayout.LayoutParams(-1,dp(40)));
            cell.setOnClickListener(v->selectApp(app));cell.setOnLongClickListener(v->{showAppOptions(app);return true;});animatePress(cell);
            row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));count++;
        }
        if(row!=null) for(int i=count%3;i>0&&i<3;i++) row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
    }
    private void selectApp(App app) {
        if(choosingSlot>=0) {pins[choosingSlot]=app.key;savePins();closeOverlay();renderHome();}
        else launch(app);
    }
    private void showAppOptions(App app) {
        LinearLayout body=beginSheet(app.label,"app-options");
        sheetAction(body,"Open app","",()->launch(app));
        for(int i=0;i<4;i++) {
            final int slot=i;App current=findApp(pins[i]);
            sheetAction(body,"Pin to dock slot "+(i+1),current==null?"Empty":current.label,()->{pins[slot]=app.key;savePins();closeOverlay();renderHome();});
        }
        sheetAction(body,"App info","",()->{closeOverlay();launcherApps.startAppDetailsActivity(app.component,android.os.Process.myUserHandle(),null,null);});
    }

    private void showAddWidget() {
        LinearLayout body=beginSheet("Add widget","add");
        sheetAction(body,"Clock","Local time · opens your alarms",()->addBuiltIn("clock",2));
        sheetAction(body,"Date","Today's date · opens your calendar",()->addBuiltIn("date",1));
        sheetAction(body,"Battery","Live battery level",()->addBuiltIn("battery",1));
        sheetAction(body,"Note","A small note stored on your phone",()->addBuiltIn("note",1));
        TextView sub=text("FROM YOUR APPS",11,"sans-serif-medium");sub.setTextColor(MUTED);sub.setLetterSpacing(.12f);sub.setPadding(dp(10),dp(24),0,dp(10));body.addView(sub);
        TextView loading=text("Loading widgets…",14,"sans-serif");loading.setPadding(dp(10),dp(14),0,dp(14));body.addView(loading);
        worker.execute(()-> {
            ArrayList<AppWidgetProviderInfo> providers=new ArrayList<>();
            try {
                for(AppWidgetProviderInfo info:widgetManager.getInstalledProvidersForProfile(android.os.Process.myUserHandle()))
                    if((info.widgetCategory&AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)!=0) providers.add(info);
                providers.sort(Comparator.comparing(info->info.loadLabel(getPackageManager()).toLowerCase(Locale.getDefault())));
            } catch(RuntimeException ignored) { }
            ui.post(()-> {
                if(destroyed||!"add".equals(overlayKind)||body.getParent()==null) return;
                body.removeView(loading);
                if(providers.isEmpty()) {loading.setText("Install an app with widgets to add one here.");body.addView(loading);}
                for(AppWidgetProviderInfo provider:providers) {
                    String app;
                    try {app=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(provider.provider.getPackageName(),0)).toString();}
                    catch(PackageManager.NameNotFoundException e) {app=provider.provider.getPackageName();}
                    sheetAction(body,provider.loadLabel(getPackageManager()),app,()->requestWidget(provider));
                }
            });
        });
    }
    private void addBuiltIn(String kind,int span) {tiles.add(new Tile(kind,-1,span,true));saveTiles();closeOverlay();renderHome();}
    private void requestWidget(AppWidgetProviderInfo provider) {
        cancelPending();pendingId=host.allocateAppWidgetId();pendingProvider=provider.provider;
        prefs.edit().putInt("pending_widget",pendingId).putString("pending_provider",pendingProvider.flattenToString()).apply();
        closeOverlay();
        Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        try {
            if(widgetManager.bindAppWidgetIdIfAllowed(pendingId,provider.getProfile(),provider.provider,options)) configureWidget();
            else {
                Intent intent=new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,pendingId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,provider.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,provider.getProfile())
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS,options);
                startActivityForResult(intent,BIND_WIDGET);
            }
        } catch(RuntimeException e) {cancelPending();toast("This widget could not be added.");}
    }
    private void configureWidget() {
        AppWidgetProviderInfo info=widgetManager.getAppWidgetInfo(pendingId);
        if(info==null) {cancelPending();toast("Widget permission was not granted.");return;}
        if(info.configure!=null) {
            try {host.startAppWidgetConfigureActivityForResult(this,pendingId,0,CONFIGURE_WIDGET,null);}
            catch(RuntimeException e) {
                boolean optional=Build.VERSION.SDK_INT>=31&&(info.widgetFeatures&AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL)!=0;
                if(optional) finishWidget();else {cancelPending();toast("This app's widget setup is unavailable.");}
            }
        } else finishWidget();
    }
    private void finishWidget() {
        if(pendingId<0||widgetManager.getAppWidgetInfo(pendingId)==null) {cancelPending();return;}
        int id=pendingId;
        if(tiles.stream().noneMatch(t->t.kind.equals("android")&&t.id==id)) tiles.add(new Tile("android",id,2,true));
        clearPending();saveTiles();renderHome();toast("Widget added. Long-press to resize it.");
    }
    private void clearPending() {pendingId=-1;pendingProvider=null;prefs.edit().remove("pending_widget").remove("pending_provider").apply();}
    private void cancelPending() {if(pendingId>=0) host.deleteAppWidgetId(pendingId);clearPending();}
    private void reconcileWidgets() {
        boolean changed=false;
        Iterator<Tile> it=tiles.iterator();
        while(it.hasNext()) {
            Tile t=it.next();if(t.kind.equals("android")&&widgetManager.getAppWidgetInfo(t.id)==null) {
                host.deleteAppWidgetId(t.id);widgetViews.remove(t.id);it.remove();changed=true;
            }
        }
        // Reclaim allocations interrupted before their binding state could be persisted.
        for(int id:host.getAppWidgetIds()) {
            if(id==pendingId) continue;
            boolean used=false;for(Tile t:tiles) if(t.kind.equals("android")&&t.id==id) {used=true;break;}
            if(!used) host.deleteAppWidgetId(id);
        }
        if(changed) {saveTiles();renderHome();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==BIND_WIDGET) {if(result==RESULT_OK) configureWidget();else cancelPending();}
        else if(request==CONFIGURE_WIDGET) {if(result==RESULT_OK) finishWidget();else cancelPending();}
        else if(request==WALLPAPER&&result==RESULT_OK&&data!=null&&data.getData()!=null) {
            Uri uri=data.getData();
            try {getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}
            catch(SecurityException e) {toast("This image cannot be kept after restart. Choose a file from your device.");return;}
            String old=prefs.getString("wallpaper",null);prefs.edit().putString("wallpaper",uri.toString()).apply();
            if(old!=null&&!old.equals(uri.toString())) try {getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION);} catch(SecurityException ignored) {}
            loadWallpaper();
        }
    }

    private void showSettings() {
        LinearLayout body=beginSheet("Your space","settings");
        sheetAction(body,"Arrange widgets","Resize, reorder, or remove",()->{closeOverlay();editing=true;renderHome();});
        sheetAction(body,"Move dock to the "+(dockLeft?"right":"left"),"Four fixed apps and a separate app menu",()-> {
            dockLeft=!dockLeft;prefs.edit().putBoolean("dock_left",dockLeft).apply();closeOverlay();renderHome();
        });
        sheetAction(body,"Choose wallpaper","Glass takes its colour from your wallpaper",()-> {
            closeOverlay();Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {startActivityForResult(pick,WALLPAPER);} catch(ActivityNotFoundException e) {toast("No image picker is installed.");}
        });
        if(prefs.contains("wallpaper")) sheetAction(body,"Use the original wallpaper","Soft lavender ribbon",()-> {
            String old=prefs.getString("wallpaper",null);prefs.edit().remove("wallpaper").apply();
            if(old!=null) try {getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION);} catch(SecurityException ignored) {}
            backdrop.setPhoto(null);closeOverlay();renderHome();
        });
        boolean isHome=Build.VERSION.SDK_INT>=29&&getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_HOME);
        sheetAction(body,isHome?"Default launcher":"Make this your default launcher",isHome?"Lumenberg Glass opens when you press Home":"Android will ask you to choose a home app",()-> {
            closeOverlay();
            try {
                if(Build.VERSION.SDK_INT>=29) {
                    RoleManager roles=getSystemService(RoleManager.class);
                    if(roles.isRoleAvailable(RoleManager.ROLE_HOME)&&!roles.isRoleHeld(RoleManager.ROLE_HOME)) startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),HOME_ROLE);
                    else openSystem(new Intent(Settings.ACTION_HOME_SETTINGS));
                } else openSystem(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch(RuntimeException e) {openSystem(new Intent(Settings.ACTION_HOME_SETTINGS));}
        });
        TextView hint=text("Long-press a dock app to replace it.\nLong-press a widget for its size controls.",14,"sans-serif");hint.setTextColor(MUTED);hint.setPadding(dp(10),dp(22),dp(10),dp(12));hint.setLineSpacing(dp(7),1);body.addView(hint);
    }
    private void loadWallpaper() {
        String wallpaper=prefs.getString("wallpaper",null);if(wallpaper==null) return;
        worker.execute(()-> {
            Bitmap bitmap=null;
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream stream=getContentResolver().openInputStream(Uri.parse(wallpaper))) {BitmapFactory.decodeStream(stream,null,bounds);}
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1800) options.inSampleSize*=2;
                try(InputStream stream=getContentResolver().openInputStream(Uri.parse(wallpaper))) {bitmap=BitmapFactory.decodeStream(stream,null,options);}
            } catch(Exception ignored) { }
            final Bitmap ready=bitmap;
            ui.post(()-> {
                if(destroyed) {if(ready!=null) ready.recycle();return;}
                if(!wallpaper.equals(prefs.getString("wallpaper",null))) {if(ready!=null) ready.recycle();return;}
                if(ready!=null) {Bitmap previous=backdrop.photo;backdrop.setPhoto(ready);renderHome();if(previous!=null) previous.recycle();}
                else toast("Wallpaper unavailable. Using the original background.");
            });
        });
    }

    private LinearLayout beginSheet(String title,String kind) {
        closeOverlay();overlayKind=kind;
        overlay=new FrameLayout(this);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(this);scrim.setBackgroundColor(0x660A0818);scrim.setContentDescription("Close menu");scrim.setOnClickListener(v->closeOverlay());
        overlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        sheet=new GlassSurface(this,backdrop,32,true);
        FrameLayout.LayoutParams shell=new FrameLayout.LayoutParams(-1,-1);shell.leftMargin=dp(14);shell.rightMargin=dp(14);shell.topMargin=topInset+dp(86);shell.bottomMargin=bottomInset+dp(20);
        overlay.addView(sheet,shell);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(22),0,dp(8),0);
        TextView heading=text(title,22,"sans-serif-medium");heading.setMaxLines(1);heading.setEllipsize(TextUtils.TruncateAt.END);header.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        header.addView(symbolButton("close","Close menu",this::closeOverlay),new LinearLayout.LayoutParams(dp(48),dp(48)));
        sheet.addView(header,new FrameLayout.LayoutParams(-1,dp(72)));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        FrameLayout.LayoutParams sc=new FrameLayout.LayoutParams(-1,-1);sc.topMargin=dp(72);sheet.addView(scroll,sc);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),0,dp(14),dp(20));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        sheet.setTranslationY(dp(18));sheet.setAlpha(.4f);sheet.animate().translationY(0).alpha(1).setDuration(180).start();
        root.requestApplyInsets();return body;
    }
    private void sheetAction(LinearLayout parent,String title,String subtitle,Runnable action) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(14),dp(12),dp(14));
        row.setBackground(pressBackground(16));row.setMinimumHeight(dp(56));row.setContentDescription(title+(subtitle.isEmpty()?"":", "+subtitle));
        TextView name=text(title,16,"sans-serif-medium");row.addView(name,new LinearLayout.LayoutParams(-1,-2));
        if(!subtitle.isEmpty()) {TextView sub=text(subtitle,13,"sans-serif");sub.setTextColor(MUTED);sub.setPadding(0,dp(5),0,0);row.addView(sub,new LinearLayout.LayoutParams(-1,-2));}
        row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void closeOverlay() {
        if(overlay!=null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(overlay.getWindowToken(),0);
            root.removeView(overlay);overlay=null;sheet=null;
        }
        overlayKind="";appGrid=null;choosingSlot=-1;
    }
    private View symbolButton(String name,String description,Runnable action) {
        FrameLayout button=new FrameLayout(this);button.setContentDescription(description);button.setBackground(pressBackground(22));
        button.addView(new SymbolView(this,name),new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
        button.setOnClickListener(v->action.run());animatePress(button);return button;
    }
    private void animatePress(View view) {
        view.setOnTouchListener((v,event)-> {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) v.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start();
            else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1).scaleY(1).setDuration(160).start();
            return false;
        });
    }
    private TextView text(String value,float size,String font) {
        TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(WHITE);text.setTypeface(Typeface.create(font,Typeface.NORMAL));return text;
    }
    private Drawable roundBackground(int color,float radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Drawable pressBackground(float radius) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x38FFFFFF),roundBackground(Color.TRANSPARENT,radius),roundBackground(Color.WHITE,radius));
    }
    private float density() {return getResources().getDisplayMetrics().density;}
    private int dp(float value) {return Math.round(value*density());}
    private String format(String pattern) {return new SimpleDateFormat(pattern,Locale.getDefault()).format(new Date());}
    private void toast(String message) {Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void openSystem(Intent intent) {
        try {startActivity(intent);} catch(ActivityNotFoundException|SecurityException e) {toast("No app is available for this action.");}
    }

    private static final class SymbolView extends View {
        final String name;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SymbolView(Context context,String name) {super(context);this.name=name;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas) {
            float size=Math.min(getWidth(),getHeight());canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);
            paint.setColor(WHITE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
            switch(name) {
                case "plus":canvas.drawLine(12,5,12,19,paint);canvas.drawLine(5,12,19,12,paint);break;
                case "close":canvas.drawLine(6,6,18,18,paint);canvas.drawLine(18,6,6,18,paint);break;
                case "check":canvas.drawLine(5,12,10,17,paint);canvas.drawLine(10,17,20,7,paint);break;
                case "up":canvas.drawLine(5,15,12,8,paint);canvas.drawLine(12,8,19,15,paint);break;
                case "more":paint.setStyle(Paint.Style.FILL);for(int x:new int[]{5,12,19}) canvas.drawCircle(x,12,1.8f,paint);break;
                case "apps":paint.setStyle(Paint.Style.FILL);for(int y:new int[]{5,12,19}) for(int x:new int[]{5,12,19}) canvas.drawRoundRect(x-1.7f,y-1.7f,x+1.7f,y+1.7f,1,1,paint);break;
                case "battery":canvas.drawRoundRect(2,6,20,18,2,2,paint);canvas.drawLine(22,10,22,14,paint);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(5,9,15,15,1,1,paint);break;
                case "resize":canvas.drawLine(5,10,5,5,paint);canvas.drawLine(5,5,10,5,paint);canvas.drawLine(14,19,19,19,paint);canvas.drawLine(19,19,19,14,paint);canvas.drawLine(5,5,10,10,paint);canvas.drawLine(14,14,19,19,paint);break;
                default:canvas.drawCircle(12,12,7,paint);
            }
            canvas.restore();
        }
    }
}
