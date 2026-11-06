package dev.flexy.dynamicisland.xposed;

import android.app.KeyguardManager;
import android.graphics.Canvas;
import android.media.session.MediaController;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XposedBridge;
import dev.flexy.dynamicisland.island.IslandController;
import dev.flexy.dynamicisland.island.IslandItem;
import dev.flexy.dynamicisland.island.IslandView;

final class ShadeMediaPlayer {
    private static final Map<View, Binding> PLAYERS = new WeakHashMap<>();
    private static final Set<Method> HOOKED = new HashSet<>();
    private static final ThreadLocal<Integer> MEASURE_HEIGHT = new ThreadLocal<>();
    private static final Map<Object, WeakReference<View>> MEDIA_ROWS = new WeakHashMap<>();
    private static final ThreadLocal<Object> BLUR_NOTIFICATION = new ThreadLocal<>();

    static void install(ClassLoader cl) {
        XLog.i("shade media hooks installing v1");
        installBackground(cl);
        String content = "com.android.systemui.statusbar.notification.row.NotificationContentView";
        Refl.hookAll(cl, "com.android.systemui.qs.SecQSPanelResourcePicker", "getMediaPlayerExpandHeight",
                SafeHook.after("notification media height", p -> {
                    Integer height = MEASURE_HEIGHT.get();
                    if (height != null) p.setResult(height);
                }));
        Refl.hookAll(cl, content, "onMeasure", new SafeHook("notification media measure") {
            final java.util.ArrayDeque<Measure> measures = new java.util.ArrayDeque<>();
            @Override protected void before(MethodHookParam p) {
                ViewGroup layout = (ViewGroup) p.thisObject;
                Measure measure = new Measure(layout);
                measures.push(measure);
                for (Map.Entry<View, Binding> entry : PLAYERS.entrySet()) {
                    View root = entry.getKey();
                    if (!descendant(root, layout)) continue;
                    IslandItem item = entry.getValue().item(root);
                    entry.getValue().updateBackground(root, item != null);
                    if (item == null) continue;
                    int width = View.MeasureSpec.getSize((Integer) p.args[0]);
                    boolean land = landscape(layout);
                    logShadeTree(layout, land);
                    entry.getValue().renderer.setShadeLandscape(land);
                    int height = (int) Math.ceil(entry.getValue().renderer.shadeCardHeight(item, width));
                    measure.apply(height);
                    break;
                }
            }
            @Override protected void after(MethodHookParam p) {
                if (!measures.isEmpty()) measures.pop().restore();
            }
        });
        Refl.hookAll(cl, "com.android.systemui.media.SecMediaControlPanel", "bind",
                SafeHook.after("shade media bind", p -> {
                    String type = String.valueOf(Refl.get(p.thisObject, "mType"));
                    if (p.hasThrowable() || !"ENR".equals(type)) return;
                    Object holder = Refl.get(p.thisObject, "mViewHolder");
                    Object root = Refl.get(holder, "playerView");
                    if (!(root instanceof ViewGroup)) return;
                    ViewGroup view = (ViewGroup) root;
                    if (!PLAYERS.containsKey(view)) {
                        hook(view.getClass(), "dispatchDraw", 1, true);
                        hook(view.getClass(), "dispatchTouchEvent", 1, false);
                        Method onMeasure = Refl.method(view.getClass(), "onMeasure", 2);
                        if (onMeasure != null && HOOKED.add(onMeasure)) {
                            XposedBridge.hookMethod(onMeasure, SafeHook.before("media body measure", q -> {
                                Binding b = PLAYERS.get(q.thisObject);
                                if (b == null) return;
                                View v = (View) q.thisObject;
                                IslandItem item = b.item(v);
                                if (item == null) return;
                                int w = View.MeasureSpec.getSize((Integer) q.args[0]);
                                b.renderer.setShadeLandscape(landscape(v));
                                int h = (int) Math.ceil(b.renderer.shadeCardHeight(item, w));
                                q.args[1] = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
                            }));
                        }
                        PLAYERS.put(view, new Binding(view, p.thisObject));
                        XLog.i("shade media renderer attached: " + view.getClass().getName());
                    }
                    view.invalidate();
                    view.requestLayout();
                }));
    }

    /** True when the shade is on a landscape screen, so media cards use the compact row. */
    private static boolean landscape(View v) {
        return v.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private static int treeLoggedState = -1;

    /** Diagnostic: logs the parent chain of the shade content once per orientation. */
    private static void logShadeTree(View from, boolean land) {
        int state = land ? 1 : 0;
        if (treeLoggedState == state) return;
        treeLoggedState = state;
        StringBuilder sb = new StringBuilder("shade tree landscape=" + land);
        View cur = from;
        for (int i = 0; i < 8 && cur != null; i++) {
            sb.append(" | ").append(cur.getClass().getSimpleName())
                    .append(' ').append(cur.getWidth()).append('x').append(cur.getHeight());
            android.view.ViewParent next = cur.getParent();
            cur = next instanceof View ? (View) next : null;
        }
        XLog.i(sb.toString());
    }

    static boolean activeRow(Object row) {
        WeakReference<View> ref = MEDIA_ROWS.get(row);
        View view = ref == null ? null : ref.get();
        Binding binding = view == null ? null : PLAYERS.get(view);
        return binding != null && binding.item(view) != null;
    }

    private static void installBackground(ClassLoader cl) {
        String base = "com.android.systemui.statusbar.notification.row.ActivatableNotificationView";
        Refl.hookAll(cl, base, "applyGradientBackground", SafeHook.before("media notification accent", p -> {
            if (activeRow(p.thisObject) && p.args.length == 3) p.args[2] = false;
        }));
        Refl.hookAll(cl, base, "isMediaNotification", SafeHook.before("media native background", p -> {
            if (p.args.length == 1 && activeRow(p.args[0])) p.setResult(false);
        }));
        Refl.hookAll(cl, base, "isBlurBackground", new SafeHook("media native blur") {
            final java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
            @Override protected void before(MethodHookParam p) {
                stack.push(new Object[]{BLUR_NOTIFICATION.get()});
                if (activeRow(p.thisObject)) {
                    Object sbn = Refl.call(Refl.get(p.thisObject, "mEntryAdapter"), "getSbn");
                    BLUR_NOTIFICATION.set(Refl.call(sbn, "getNotification"));
                }
            }
            @Override protected void after(MethodHookParam p) {
                Object old = stack.pop()[0];
                if (old == null) BLUR_NOTIFICATION.remove(); else BLUR_NOTIFICATION.set(old);
            }
        });
        Refl.hookAll(android.app.Notification.class, "isColorized", SafeHook.before("media blur color eligibility", p -> {
            if (p.thisObject == BLUR_NOTIFICATION.get()) p.setResult(false);
        }));
    }

    private static boolean descendant(View view, View parent) {
        for (android.view.ViewParent p = view.getParent(); p != null; p = p.getParent())
            if (p == parent) return true;
        return false;
    }

    private static final class Measure {
        final Object layout;
        final Integer previous = MEASURE_HEIGHT.get();
        final String[] fields = {"mSmallHeight", "mMinContractedHeight", "mNotificationMaxHeight", "mHeadsUpHeight"};
        final int[] old = new int[fields.length];
        final java.util.Map<View, Integer> heights = new java.util.HashMap<>();
        boolean changed;
        Measure(Object layout) { this.layout = layout; }
        void apply(int height) {
            changed = true;
            MEASURE_HEIGHT.set(height);
            for (int i = 0; i < fields.length; i++) {
                old[i] = Refl.getInt(layout, fields[i], height);
                Refl.set(layout, fields[i], height);
            }
            for (String field : new String[]{"mExpandedChild", "mContractedChild"}) {
                Object child = Refl.get(layout, field);
                if (child instanceof View && ((View) child).getLayoutParams() != null) {
                    View v = (View) child;
                    heights.put(v, v.getLayoutParams().height);
                    v.getLayoutParams().height = height;
                }
            }
        }
        void restore() {
            if (changed) {
                for (int i = 0; i < fields.length; i++) Refl.set(layout, fields[i], old[i]);
                for (Map.Entry<View, Integer> e : heights.entrySet()) e.getKey().getLayoutParams().height = e.getValue();
            }
            if (previous == null) MEASURE_HEIGHT.remove(); else MEASURE_HEIGHT.set(previous);
        }
    }

    private static void hook(Class<?> cls, String name, int count, boolean draw) {
        Method method = Refl.method(cls, name, count);
        if (method == null) throw new IllegalStateException(name);
        if (HOOKED.contains(method)) return;
        XposedBridge.hookMethod(method, SafeHook.before("shade media " + name, p -> {
            Binding b = PLAYERS.get(p.thisObject);
            if (b == null) return;
            View view = (View) p.thisObject;
            try {
                IslandItem item = b.item(view);
                b.updateBackground(view, item != null);
                if (item == null) {
                    b.cancel();
                    return;
                }
                if (draw) {
                    b.draw(view, (Canvas) p.args[0], item);
                    p.setResult(null);
                } else {
                    p.setResult(b.touch(view, (MotionEvent) p.args[0], item));
                }
            } catch (Throwable error) {
                b.cancel();
                b.updateBackground(view, false);
                PLAYERS.remove(view);
                view.invalidate();
                XLog.e("shade media restored stock player", error);
            }
        }));
        HOOKED.add(method);
    }

    private static final class Binding {
        final WeakReference<Object> panel;
        final IslandView renderer;
        final float slop;
        IslandItem down;
        float x, y, scale = 1, offsetY;
        boolean seeking, moved;
        WeakReference<View> backgroundRow;
        boolean backgroundActive;

        Binding(View view, Object panel) {
            this.panel = new WeakReference<>(panel);
            renderer = new IslandView(view.getContext(), null);
            slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        }

        void updateBackground(View view, boolean active) {
            View row = null;
            for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof View && parent.getClass().getName().endsWith("ExpandableNotificationRow")) {
                    row = (View) parent;
                    break;
                }
            }
            if (row == null) return;
            View old = backgroundRow == null ? null : backgroundRow.get();
            if (old == row && backgroundActive == active) return;
            backgroundRow = new WeakReference<>(row);
            backgroundActive = active;
            if (active) MEDIA_ROWS.put(row, new WeakReference<>(view)); else MEDIA_ROWS.remove(row);
            View target = row;
            Runnable reinit = () -> {
                Refl.call(target, "initBackground");
                if (active) Refl.call(target, "applyGradientBackground", target.getWidth(), target.getHeight(), false);
                Refl.call(target, "updateBackground");
                target.invalidate();
            };
            row.post(reinit);
            if (active) row.postDelayed(reinit, 700);
        }

        IslandItem item(View view) {
            KeyguardManager keyguard = view.getContext().getSystemService(KeyguardManager.class);
            AccessibilityManager accessibility = view.getContext().getSystemService(AccessibilityManager.class);
            if ((keyguard != null && keyguard.isKeyguardLocked())
                    || (accessibility != null && accessibility.isTouchExplorationEnabled())) return null;
            Object p = panel.get();
            Object holder = Refl.get(p, "mViewHolder");
            Object options = Refl.get(holder, "options");
            if (options instanceof View && ((View) options).getVisibility() == View.VISIBLE) return null;
            Object controller = Refl.get(p, "mController");
            IslandController source = IslandController.get();
            return controller instanceof MediaController && source != null
                    ? source.mediaItemForSession(((MediaController) controller).getSessionToken()) : null;
        }

        void draw(View view, Canvas canvas, IslandItem item) {
            renderer.setShadeLandscape(landscape(view));
            float height = renderer.shadeCardHeight(item, view.getWidth());
            scale = Math.min(1f, view.getHeight() / Math.max(1f, height));
            if (scale <= 0) return;
            offsetY = Math.max(0, (view.getHeight() - height * scale) / 2f);
            int save = canvas.save();
            try {
                canvas.translate((view.getWidth() * (1 - scale)) / 2f, offsetY);
                canvas.scale(scale, scale);
                renderer.drawShadeCard(canvas, item, view.getWidth());
            } finally {
                canvas.restoreToCount(save);
            }
            if (view.isShown() && view.getWindowVisibility() == View.VISIBLE) {
                View row = backgroundRow == null ? null : backgroundRow.get();
                if (backgroundActive) ShadeCardTheme.ensureBlur(row);
                view.postInvalidateDelayed(renderer.shadeButtonAnimating() ? 16 : item.playing ? 33 : 250);
            }
        }

        void open(View view, IslandItem item) {
            IslandController source = IslandController.get();
            XLog.i("shade media open " + item.packageName + " intent=" + (item.contentIntent != null));
            if (source != null) source.onOpenItem(item, null);
            try {
                Object sbm = view.getContext().getSystemService("statusbar");
                sbm.getClass().getMethod("collapsePanels").invoke(sbm);
            } catch (Throwable t) {
                XLog.e("shade media collapse failed", t);
            }
        }

        void cancel() {
            renderer.shadeButtonUp();
            renderer.shadeMediaSeekEnd(false);
            down = null;
            seeking = false;
        }

        boolean touch(View view, MotionEvent event, IslandItem item) {
            float px = (event.getX() - view.getWidth() * (1 - scale) / 2f) / scale;
            float py = (event.getY() - offsetY) / scale;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = item;
                    x = px;
                    y = py;
                    moved = false;
                    seeking = renderer.shadeMediaSeekStart(item, view.getWidth(), px, py);
                    if (!seeking && renderer.shadeButtonDown(item, view.getWidth(), px, py)) view.invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (down == null) return true;
                    if (!moved && (Math.abs(px - x) > slop || Math.abs(py - y) > slop)) {
                        moved = true;
                        renderer.shadeButtonUp();
                        view.invalidate();
                    }
                    if (seeking) {
                        if (Math.abs(px - x) > slop && view.getParent() != null)
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        renderer.shadeMediaSeekMove(px);
                        view.invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (down != null && java.util.Objects.equals(down.key, item.key)) {
                        if (seeking) renderer.shadeMediaSeekEnd(true);
                        else if (!moved && !renderer.shadeCardTap(item, view.getWidth(), x, y)) open(view, item);
                    }
                    cancel();
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    view.invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancel();
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }
    }
}
