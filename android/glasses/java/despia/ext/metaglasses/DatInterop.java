package despia.ext.metaglasses;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

/**
 * The few Kotlin coroutine seams the DAT Android SDK exposes, used from Java: a suspend function
 * called with a hand-made Continuation, and a Flow collected in a scope. The fixture's typed module
 * is Java (the generated module applies no Kotlin plugin), and kotlin-stdlib and
 * kotlinx-coroutines arrive with mwdat-core's compile-scope dependencies.
 */
final class DatInterop {
    private DatInterop() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A suspend function, called with the continuation it must resume. */
    interface SuspendCall<T> {
        Object invoke(Continuation<? super T> continuation);
    }

    /** Receives a suspend call's value or its failure, on the main thread, exactly once. */
    interface Done<T> {
        void done(T value, Throwable error);
    }

    /** Receives each value a Flow emits, on the scope's dispatcher. */
    interface Each<T> {
        void each(T value);
    }

    @SuppressWarnings("unchecked")
    static <T> void call(SuspendCall<T> call, Done<T> done) {
        AtomicBoolean fired = new AtomicBoolean(false);
        Continuation<T> continuation = new Continuation<T>() {
            @Override
            public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(Object result) {
                Throwable error = null;
                T value = null;
                try {
                    ResultKt.throwOnFailure(result);
                    value = (T) result;
                } catch (Throwable t) {
                    error = t;
                }
                deliver(fired, done, value, error);
            }
        };
        Object r;
        try {
            r = call.invoke(continuation);
        } catch (Throwable t) {
            deliver(fired, done, null, t);
            return;
        }
        if (r != IntrinsicsKt.getCOROUTINE_SUSPENDED()) deliver(fired, done, (T) r, null);
    }

    private static <T> void deliver(AtomicBoolean fired, Done<T> done, T value, Throwable error) {
        if (!fired.compareAndSet(false, true)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            done.done(value, error);
        } else {
            MAIN.post(() -> done.done(value, error));
        }
    }

    /** Collects a Flow in the scope; cancel the returned job (or the scope) to stop. */
    static <T> Job collect(CoroutineScope scope, Flow<T> flow, Each<T> each) {
        return FlowKt.launchIn(FlowKt.onEach(flow, (value, continuation) -> {
            each.each(value);
            return Unit.INSTANCE;
        }), scope);
    }

    /** A scope on the main thread (SupervisorJob + Dispatchers.Main). */
    static CoroutineScope mainScope() {
        return CoroutineScopeKt.MainScope();
    }

    /** A scope on the shared background pool, for per-frame work. */
    static CoroutineScope backgroundScope() {
        return CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
    }

    static void cancel(CoroutineScope scope) {
        if (scope != null) CoroutineScopeKt.cancel(scope, (CancellationException) null);
    }

    static void cancel(Job job) {
        if (job != null) job.cancel((CancellationException) null);
    }
}
