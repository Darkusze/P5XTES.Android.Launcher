package dev.allofus.fusioncore.hooks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.io.File;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import dalvik.system.PathClassLoader;
import dev.allofus.fusioncore.tools.NativeLibraryManager;
import dev.allofus.fusioncore.R;
import dev.allofus.fusioncore.tools.CustomContextWrapper;

public final class GameRuntime {
    private static android.app.Application gameApplication;
    private static volatile ClassLoader gameClassLoader;
    /** Writes diag_*.txt (package, resources, extras, class loaders) to /sdcard/FusionCore before each game activity starts. */
    private static final boolean DIAGNOSTICS = false;
    private GameRuntime() {}

    /** ClassLoader of the virtual game package (null until {@link #install(Context)} runs). */
    public static ClassLoader getGameClassLoader() { return gameClassLoader; }

    /**
     * Makes every extra of {@code intent} deserialize with {@code loader}.
     * <p>
     * {@link Intent#setExtrasClassLoader(ClassLoader)} alone is not enough when the extras bundle was
     * already (partially) unparcelled with another loader: lazily-held values (Serializable, Parcelable)
     * keep the loader they were created with. So after setting it we round-trip the extras through a
     * Parcel, which recreates the lazy values bound to {@code loader}.
     */
    static void useGameLoader(Intent intent, ClassLoader loader) {
        if (intent == null || loader == null) return;
        intent.setExtrasClassLoader(loader);
        Parcel parcel = Parcel.obtain();
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            extras.setClassLoader(loader);
            parcel.writeBundle(extras);
            parcel.setDataPosition(0);
            Bundle fresh = parcel.readBundle(loader);
            if (fresh != null) {
                fresh.setClassLoader(loader);
                intent.replaceExtras(fresh);
                intent.setExtrasClassLoader(loader);
            }
        } catch (RuntimeException e) {
            Log.w("GameRuntime", "Could not rebind intent extras to the game class loader", e);
        } finally {
            parcel.recycle();
        }
    }

    public static android.app.Application getGameApplication() { return gameApplication; }

    public static void initializeApplication(Context gameContext, Context fusionContext)
            throws ReflectiveOperationException {
        String className = gameContext.getApplicationInfo().className;
        if (gameApplication != null || className == null) return;
        android.app.Application application = (android.app.Application) gameContext.getClassLoader()
                .loadClass(className).getDeclaredConstructor().newInstance();
        Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, new CustomContextWrapper(gameContext, fusionContext, application));
        gameApplication = application;
        // Android installs the manifest's ContentProviders after Application.attach() and BEFORE
        // Application.onCreate(). Many SDKs initialise themselves from a provider (FirebaseInitProvider,
        // androidx.startup, Facebook...). FusionCore never ran them, so e.g. the game's SDK failed with
        // "Default FirebaseApp is not initialized in this process".
        installContentProviders(application, gameContext);
        application.onCreate();
        Log.i("GameRuntime", "Initialized application " + className);
    }

    /** Instantiates and initialises the game's manifest ContentProviders, like ActivityThread would. */
    private static void installContentProviders(android.app.Application application, Context gameContext) {
        try {
            android.content.pm.PackageInfo packageInfo = gameContext.getPackageManager().getPackageInfo(
                    gameContext.getPackageName(), android.content.pm.PackageManager.GET_PROVIDERS);
            if (packageInfo == null || packageInfo.providers == null) return;
            String mainProcess = gameContext.getApplicationInfo().processName;
            ArrayList<android.content.pm.ProviderInfo> providers =
                    new ArrayList<>(java.util.Arrays.asList(packageInfo.providers));
            // Same order the system uses: highest initOrder first.
            providers.sort((a, b) -> Integer.compare(b.initOrder, a.initOrder));
            for (android.content.pm.ProviderInfo info : providers) {
                if (info.processName != null && mainProcess != null && !info.processName.equals(mainProcess)) {
                    continue; // provider of another process of the game
                }
                try {
                    Class<?> providerClass = gameContext.getClassLoader().loadClass(info.name);
                    java.lang.reflect.Constructor<?> constructor = providerClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    android.content.ContentProvider provider = (android.content.ContentProvider) constructor.newInstance();
                    provider.attachInfo(application, info);
                    Log.i("GameRuntime", "Installed content provider " + info.name);
                } catch (Throwable t) {
                    Log.w("GameRuntime", "Could not install content provider " + info.name, t);
                }
            }
        } catch (Throwable t) {
            Log.w("GameRuntime", "Could not install content providers", t);
        }
    }

    public static Context prepareGameContext(Context gameContext, ClassLoader fusionLoader)
            throws ReflectiveOperationException {
        android.content.pm.ApplicationInfo info = gameContext.getApplicationInfo();
        ArrayList<String> apks = new ArrayList<>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(apks, info.splitSourceDirs);
        ClassLoader gameLoader = new GameClassLoader(String.join(File.pathSeparator, apks),
                info.nativeLibraryDir, fusionLoader);
        return new ContextWrapper(gameContext) {
            @Override public ClassLoader getClassLoader() { return gameLoader; }
        };
    }

    /**
     * Loads the game's own classes (and the SDKs bundled in its APKs) first.
     * <p>
     * IMPORTANT: the parent is the boot class loader, NOT the FusionCore (host) loader. The host and the game
     * ship separate copies of many androidx classes (ConstraintLayout, ContentFrameLayout, ...), and
     * {@link LayoutInflater} keeps ONE process-wide cache of view constructors keyed by class name, accepting
     * a cached constructor if its loader is the inflater context's loader OR ANY OF ITS PARENTS. With the host
     * loader as parent, a view class cached by a FusionCore AppCompat activity was reused by the game's
     * inflater and the SDK failed with "X cannot be cast to X". Host classes are still reachable, but only by
     * explicit delegation in {@link #loadClass}, so they never appear in this loader's parent chain.
     */
    private static final class GameClassLoader extends PathClassLoader {
        private final ClassLoader hostLoader;

        GameClassLoader(String dexPath, String libraryPath, ClassLoader hostLoader) {
            super(dexPath, libraryPath, bootLoader());
            this.hostLoader = hostLoader;
        }

        private static ClassLoader bootLoader() {
            ClassLoader boot = Context.class.getClassLoader();
            return boot != null ? boot : ClassLoader.getSystemClassLoader().getParent();
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.startsWith("dev.allofus.fusioncore.")) {
                        // FusionCore's own API (hooks, bridges): always the host's single copy.
                        loaded = hostLoader.loadClass(name);
                    } else {
                        boolean platform = name.startsWith("java.") || name.startsWith("javax.") ||
                                name.startsWith("android.") || name.startsWith("dalvik.") ||
                                name.startsWith("sun.") || name.startsWith("org.w3c.") ||
                                name.startsWith("org.xml.");
                        if (!platform) {
                            try { loaded = findClass(name); }
                            catch (ClassNotFoundException ignored) { }
                        }
                        if (loaded == null) {
                            try {
                                loaded = super.loadClass(name, false);          // boot class path
                            } catch (ClassNotFoundException notInBoot) {
                                loaded = hostLoader.loadClass(name);            // same fallback the old parent gave
                            }
                        }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override public String findLibrary(String name) {
            String override = NativeLibraryManager.findLibrary(name);
            return override != null ? override : super.findLibrary(name);
        }
    }

    public static void install(Context gameContext) throws ReflectiveOperationException {
        gameClassLoader = gameContext.getClassLoader();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Method currentThread = threadClass.getDeclaredMethod("currentActivityThread");
        currentThread.setAccessible(true);
        Object thread = currentThread.invoke(null);
        Field instrumentation = threadClass.getDeclaredField("mInstrumentation");
        instrumentation.setAccessible(true);
        Instrumentation original = (Instrumentation) instrumentation.get(thread);
        if (!(original instanceof GameInstrumentation)) {
            instrumentation.set(thread, new GameInstrumentation(original, gameContext));
        }
        guardMainLooperAgainstGmsPackageErrors();
        Log.i("GameRuntime", "Installed activity loading");
    }

    private static boolean mainLooperGuarded;

    /**
     * Google Play Services checks that the package name a client claims belongs to the calling UID. The game's
     * SDKs (Firebase Analytics, ...) claim the game's package, but inside FusionCore the real caller is
     * dev.allofus.fusioncore, so GMS answers "SecurityException: Unknown calling package name '...'". The
     * SDKs do not expect that exception and it escapes on the main thread, killing the app. A client cannot
     * legitimately impersonate another package, so the call simply cannot succeed: keep the main loop alive
     * and drop only that specific failure (everything else still crashes as usual).
     */
    private static synchronized void guardMainLooperAgainstGmsPackageErrors() {
        if (mainLooperGuarded) return;
        mainLooperGuarded = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            while (true) {
                try {
                    android.os.Looper.loop();
                    return; // only when the looper quits
                } catch (Throwable t) {
                    if (!isGmsCallingPackageError(t)) {
                        if (t instanceof RuntimeException) throw (RuntimeException) t;
                        if (t instanceof Error) throw (Error) t;
                        throw new RuntimeException(t);
                    }
                    Log.w("GameRuntime", "Ignored Google Play Services calling-package error: " + t.getMessage());
                }
            }
        });
    }

    private static boolean isGmsCallingPackageError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (!(t instanceof SecurityException)) continue;
            String message = String.valueOf(t.getMessage());
            if (!message.contains("Unknown calling package name")) continue;
            for (StackTraceElement frame : t.getStackTrace()) {
                String file = String.valueOf(frame.getFileName());
                if (file.contains("com.google.android.gms") || frame.getClassName().startsWith("com.google.android.gms")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class GameInstrumentation extends Instrumentation {
        private final Instrumentation original;
        private final Context gameContext;
        private final Map<Activity, Intent> pendingIntents = Collections.synchronizedMap(new WeakHashMap<>());
        private final java.util.Set<Activity> gameActivities = Collections.newSetFromMap(new WeakHashMap<>());

        GameInstrumentation(Instrumentation original, Context gameContext) {
            this.original = original;
            this.gameContext = gameContext;
        }

        @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            intent.setExtrasClassLoader(gameContext.getClassLoader());
            if (!InstrumentationHooks.isDynamicIntent(intent)) return original.newActivity(loader, name, intent);
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            if (target == null || target.getComponent() == null) {
                throw new InstantiationException("Missing dynamic activity intent");
            }
            target = new Intent(target);
            target.setExtrasClassLoader(gameContext.getClassLoader());
            if (intent.hasExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG)) {
                target.putExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG,
                        intent.<android.os.Parcelable>getParcelableExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG));
            }
            if (intent.hasExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION)) {
                target.putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                        intent.getIntExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION,
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            }
            Activity activity = original.newActivity(gameContext.getClassLoader(),
                    target.getComponent().getClassName(), target);
            pendingIntents.put(activity, target);
            gameActivities.add(activity);
            return activity;
        }

        /**
         * Forces every Resources-typed instance field in the activity's class hierarchy (ContextThemeWrapper,
         * AppCompatActivity, the SDK's own base activity...) to the game's Resources, and drops the
         * ContextThemeWrapper override configuration so Resources are never rebuilt from another context.
         */
        private void pinGameResources(Activity activity) {
            android.content.res.Resources gameRes = gameContext.getResources();
            for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != android.content.res.Resources.class) continue;
                    try {
                        f.setAccessible(true);
                        f.set(activity, gameRes);
                    } catch (Throwable t) {
                        Log.w("GameRuntime", "Could not pin Resources field " + c.getName() + "." + f.getName(), t);
                    }
                }
            }
            try {
                Field override = ContextThemeWrapper.class.getDeclaredField("mOverrideConfiguration");
                override.setAccessible(true);
                override.set(activity, null);
            } catch (Throwable t) {
                Log.w("GameRuntime", "Could not clear mOverrideConfiguration", t);
            }
        }

        private void prepare(Activity activity) {
            Intent target = pendingIntents.remove(activity);
            if (target == null) return;
            try {
                activity.setIntent(target);
                Context fusionBase = activity.getBaseContext();
                Field base = ContextWrapper.class.getDeclaredField("mBase");
                base.setAccessible(true);
                base.set(activity, new CustomContextWrapper(gameContext, fusionBase));
                if (gameApplication != null) {
                    Field application = Activity.class.getDeclaredField("mApplication");
                    application.setAccessible(true);
                    application.set(activity, gameApplication);
                }
                Field resources = ContextThemeWrapper.class.getDeclaredField("mResources");
                resources.setAccessible(true);
                // Pin the activity to the game's own Resources. With null, ContextThemeWrapper rebuilds them
                // lazily and (for activities that apply an override configuration, e.g. AppCompat ones such as
                // the SDK's ActivityLogin) ends up with a different Resources object that cannot resolve the
                // game's resource ids (getIdentifier == 0 / "Resource ID #0x0").
                resources.set(activity, gameContext.getResources());
                pinGameResources(activity);
                ActivityInfo info = gameContext.getPackageManager().getActivityInfo(
                        new android.content.ComponentName(gameContext.getPackageName(), activity.getClass().getName()), 0);
                android.content.res.Resources.Theme theme = activity.getResources().newTheme();
                theme.applyStyle(info.getThemeResource() != 0 ? info.getThemeResource()
                        : android.R.style.Theme_Black_NoTitleBar, true);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    activity.setTheme(theme);
                } else {
                    Field oldTheme = ContextThemeWrapper.class.getDeclaredField("mTheme");
                    oldTheme.setAccessible(true);
                    oldTheme.set(activity, theme);
                }
                InstrumentationHooks.applyTargetOrientation(activity);
                if (target.hasExtra(InstrumentationHooks.EXTRA_FUSION_CONFIG)) {
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    Context loadingTheme = new ContextThemeWrapper(fusionBase, androidx.appcompat.R.style.Theme_AppCompat);
                    decor.addView(LayoutInflater.from(loadingTheme).inflate(R.layout.loading_view, decor, false));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare activity", e);
            }
        }

        /**
         * Must run after {@link #prepare(Activity)} (which swaps in the original intent) and before the
         * game's onCreate reads its extras (e.g. a Serializable FragmentLoginToken).
         */
        private void applyGameClassLoader(Activity activity, Bundle state) {
            if (!gameActivities.contains(activity)) return;
            ClassLoader loader = gameContext.getClassLoader();
            useGameLoader(activity.getIntent(), loader);              // 1. intent extras
            if (state != null) state.setClassLoader(loader);          // 2. saved state
            Thread.currentThread().setContextClassLoader(loader);     // 3. context class loader
            if (DIAGNOSTICS) logDiagnostics(activity);
        }

        /** Who defines ConstraintLayout, and which loader does the activity's inflater use? */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void appendViewClassDiagnostics(StringBuilder sb, Activity activity) {
            final String viewClass = "androidx.constraintlayout.widget.ConstraintLayout";
            ClassLoader gl = gameContext.getClassLoader();
            ClassLoader hl = GameRuntime.class.getClassLoader();
            sb.append("gameLoader=").append(describe(gl)).append(" gameLoader.parent=").append(describe(gl.getParent()))
                    .append(" hostLoader=").append(describe(hl))
                    .append(" activity.getClassLoader=").append(describe(activity.getClassLoader()))
                    .append(" contextClassLoader=").append(describe(Thread.currentThread().getContextClassLoader())).append('\n');
            for (ClassLoader loader : new ClassLoader[] {gl, hl}) {
                try {
                    Class<?> c = Class.forName(viewClass, false, loader);
                    sb.append("ConstraintLayout via ").append(describe(loader)).append(" -> class ").append(describe(c))
                            .append(" defined by ").append(describe(c.getClassLoader())).append('\n');
                } catch (Throwable t) {
                    sb.append("ConstraintLayout via ").append(describe(loader)).append(" -> ").append(t).append('\n');
                }
            }
            try {
                Class<?> base = Class.forName("com.wpsdk.global.base.ui.BaseActivity", false, gl);
                sb.append("SDK BaseActivity defined by ").append(describe(base.getClassLoader())).append('\n');
            } catch (Throwable t) {
                sb.append("SDK BaseActivity lookup failed: ").append(t).append('\n');
            }
            try {
                LayoutInflater li = LayoutInflater.from(activity);
                sb.append("inflater=").append(describe(li)).append(" ctx=").append(describe(li.getContext()))
                        .append(" ctxLoader=").append(describe(li.getContext().getClassLoader()))
                        .append(" factory2=").append(describe(li.getFactory2())).append('\n');
            } catch (Throwable t) {
                sb.append("inflater lookup failed: ").append(t).append('\n');
            }
            try {
                Field cache = LayoutInflater.class.getDeclaredField("sConstructorMap");
                cache.setAccessible(true);
                Map map = (Map) cache.get(null);
                synchronized (map) {
                    for (Object o : map.entrySet()) {
                        Map.Entry e = (Map.Entry) o;
                        String key = String.valueOf(e.getKey());
                        if (!key.startsWith("androidx.") && !key.startsWith("com.google.")) continue;
                        Object v = e.getValue();
                        sb.append("  LayoutInflater cache ").append(key).append(" -> ")
                                .append(v instanceof java.lang.reflect.Constructor
                                        ? describe(((java.lang.reflect.Constructor) v).getDeclaringClass().getClassLoader())
                                        : String.valueOf(v)).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("LayoutInflater cache dump failed: ").append(t).append('\n');
            }
            try {
                Class<?> avi = Class.forName("androidx.appcompat.app.AppCompatViewInflater", false, gl);
                sb.append("AppCompatViewInflater defined by ").append(describe(avi.getClassLoader()));
                Field f = avi.getDeclaredField("sConstructorMap");
                f.setAccessible(true);
                Object m = f.get(null);
                Object cached = m.getClass().getMethod("get", Object.class).invoke(m, viewClass);
                sb.append(", cached ConstraintLayout ctor loader=")
                        .append(cached instanceof java.lang.reflect.Constructor
                                ? describe(((java.lang.reflect.Constructor) cached).getDeclaringClass().getClassLoader())
                                : String.valueOf(cached)).append('\n');
            } catch (Throwable t) {
                sb.append("AppCompatViewInflater cache check failed: ").append(t).append('\n');
            }
        }

        private static String describe(Object o) {
            return o == null ? "null" : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
        }

        /** Temporary diagnostics: what does the game activity see for package / resources / extras? */
        private void logDiagnostics(Activity activity) {
            final String tag = "GameRuntime";
            StringBuilder sb = new StringBuilder("GameRuntime diagnostics\n");
            try {
                android.content.res.Resources res = activity.getResources();
                String pkg = activity.getPackageName();
                sb.append("activity=").append(activity.getClass().getName())
                        .append(" loader=").append(activity.getClass().getClassLoader()).append('\n');
                sb.append("packageName=").append(pkg)
                        .append(" appInfo.packageName=").append(activity.getApplicationInfo().packageName)
                        .append(" gameContext.packageName=").append(gameContext.getPackageName()).append('\n');
                sb.append("resourcePath=").append(activity.getPackageResourcePath())
                        .append(" gameResourcePath=").append(gameContext.getPackageResourcePath()).append('\n');
                sb.append("resources=").append(res).append(" gameResources=").append(gameContext.getResources()).append('\n');
                sb.append("component=").append(activity.getComponentName()).append('\n');
                try {
                    sb.append("getResources declared in: ")
                            .append(activity.getClass().getMethod("getResources").getDeclaringClass().getName()).append('\n');
                    sb.append("getAssets declared in: ")
                            .append(activity.getClass().getMethod("getAssets").getDeclaringClass().getName()).append('\n');
                    sb.append("getBaseContext=").append(describe(activity.getBaseContext())).append('\n');
                    for (Class<?> c = activity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            Class<?> t = f.getType();
                            if (t != android.content.res.Resources.class && t != Context.class
                                    && t != android.content.res.Configuration.class
                                    && t != android.content.res.AssetManager.class) continue;
                            try {
                                f.setAccessible(true);
                                sb.append("  field ").append(c.getSimpleName()).append('.').append(f.getName())
                                        .append(" : ").append(t.getSimpleName()).append(" = ")
                                        .append(describe(f.get(activity))).append('\n');
                            } catch (Throwable e) {
                                sb.append("  field ").append(c.getSimpleName()).append('.').append(f.getName())
                                        .append(" unreadable: ").append(e.getClass().getSimpleName()).append('\n');
                            }
                        }
                    }
                } catch (Throwable t) {
                    sb.append("hierarchy dump failed: ").append(t).append('\n');
                }

                appendViewClassDiagnostics(sb, activity);

                Intent intent = activity.getIntent();
                Bundle extras = intent != null ? intent.getExtras() : null;
                if (extras == null) {
                    sb.append("extras=null\n");
                } else {
                    for (String key : extras.keySet()) {
                        Object value;
                        try { value = extras.get(key); } catch (RuntimeException e) { value = "<error " + e + ">"; }
                        sb.append("extra ").append(key).append(" = ")
                                .append(value == null ? "null" : value.getClass().getName() + " : " + value).append('\n');
                    }
                }

                // Can the activity's Resources resolve the SDK's "login" layouts, by id and by name?
                String[] rClasses = {
                        gameContext.getPackageName() + ".R$layout",
                        "com.wpsdk.global.base.R$layout",
                        "com.wpsdk.global.core.R$layout"
                };
                for (String className : rClasses) {
                    try {
                        Class<?> c = Class.forName(className, false, gameContext.getClassLoader());
                        sb.append("R class ").append(className).append(" found\n");
                        int shown = 0;
                        for (Field f : c.getFields()) {
                            if (f.getType() != int.class || shown >= 30) continue;
                            String name = f.getName();
                            if (!name.toLowerCase().contains("login")) continue;
                            shown++;
                            int id = f.getInt(null);
                            int byName = res.getIdentifier(name, "layout", pkg);
                            String resolved;
                            try { resolved = res.getResourceName(id); } catch (Exception e) { resolved = "<NOT FOUND>"; }
                            sb.append("  ").append(name).append(" id=0x").append(Integer.toHexString(id))
                                    .append(" getIdentifier=0x").append(Integer.toHexString(byName))
                                    .append(" resourceName=").append(resolved).append('\n');
                        }
                    } catch (ClassNotFoundException e) {
                        sb.append("R class ").append(className).append(" NOT found\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("diag failed: ").append(Log.getStackTraceString(t)).append('\n');
            }

            String text = sb.toString();
            Log.i(tag, text);
            try (java.io.FileWriter writer = new java.io.FileWriter(new File(
                    dev.allofus.fusioncore.tools.Utilities.getExternalFusionCoreDirectory(null, null),
                    "diag_" + System.currentTimeMillis() + ".txt"), false)) {
                writer.write(text);
            } catch (Exception e) {
                Log.w(tag, "Could not write diag file", e);
            }
        }

        @Override public void callActivityOnCreate(Activity activity, Bundle state) {
            prepare(activity);
            applyGameClassLoader(activity, state);
            original.callActivityOnCreate(activity, state);
        }
        @Override public void callActivityOnCreate(Activity activity, Bundle state, PersistableBundle persistentState) {
            prepare(activity);
            applyGameClassLoader(activity, state);
            original.callActivityOnCreate(activity, state, persistentState);
        }
        @Override public void callActivityOnRestoreInstanceState(Activity activity, Bundle state) {
            if (state != null && gameActivities.contains(activity)) {
                state.setClassLoader(gameContext.getClassLoader());
            }
            original.callActivityOnRestoreInstanceState(activity, state);
        }
        @Override public void callActivityOnResume(Activity activity) {
            if (gameActivities.contains(activity)) InstrumentationHooks.applyTargetOrientation(activity);
            original.callActivityOnResume(activity);
        }
        @Override public void callActivityOnNewIntent(Activity activity, Intent intent) {
            Intent target = InstrumentationHooks.resolveOriginalIntent(intent);
            Intent delivered = target != null ? target : intent;
            if (gameActivities.contains(activity)) {
                useGameLoader(delivered, gameContext.getClassLoader());
            }
            original.callActivityOnNewIntent(activity, delivered);
        }
        @Override public void callActivityOnDestroy(Activity activity) {
            pendingIntents.remove(activity);
            gameActivities.remove(activity);
            original.callActivityOnDestroy(activity);
        }
        @Override public boolean onException(Object object, Throwable error) {
            return original.onException(object, error);
        }

        private Intent wrap(Intent intent) {
            if (intent == null || intent.getComponent() == null || InstrumentationHooks.isDynamicIntent(intent)) return intent;
            String packageName = intent.getComponent().getPackageName();
            String className = intent.getComponent().getClassName();
            if (!packageName.equals(gameContext.getPackageName()) &&
                    !packageName.equals(dev.allofus.fusioncore.BuildConfig.APPLICATION_ID)) return intent;
            if (className.startsWith("dev.allofus.fusioncore.")) return intent;
            Intent wrapped = InstrumentationHooks.getInjectedIntent(intent);
            wrapped.setComponent(new android.content.ComponentName(
                    dev.allofus.fusioncore.BuildConfig.APPLICATION_ID,
                    dev.allofus.fusioncore.SecondaryStubActivity.class.getName()));
            return wrapped;
        }

        private ActivityResult start(Class<?> targetType, Context who, IBinder contextThread,
                                     IBinder token, Object target, Intent intent, int requestCode,
                                     Bundle options, UserHandle user) {
            try {
                Class<?>[] types = user == null
                        ? new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class}
                        : new Class<?>[] {Context.class, IBinder.class, IBinder.class, targetType, Intent.class, int.class, Bundle.class, UserHandle.class};
                Method method = Instrumentation.class.getDeclaredMethod("execStartActivity", types);
                method.setAccessible(true);
                Object[] args = user == null
                        ? new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options}
                        : new Object[] {who, contextThread, token, target, wrap(intent), requestCode, options, user};
                return (ActivityResult) method.invoke(original, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Cannot launch activity", cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot dispatch activity", e);
            }
        }

        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options) {
            return start(Activity.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               String target, Intent intent, int code, Bundle options) {
            return start(String.class, who, thread, token, target, intent, code, options, null);
        }
        public ActivityResult execStartActivity(Context who, IBinder thread, IBinder token,
                                               Activity target, Intent intent, int code, Bundle options, UserHandle user) {
            return start(Activity.class, who, thread, token, target, intent, code, options, user);
        }
    }
}