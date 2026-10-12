package dev.rikumi.flymemod;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** One framework interception for the module's ImageView draw decorators. */
final class ImageDrawHookChain implements XposedInterface.Hooker {
    private volatile XposedInterface.Hooker[] hooks = new XposedInterface.Hooker[0];

    synchronized boolean add(XposedInterface.Hooker hook) {
        XposedInterface.Hooker[] next = Arrays.copyOf(hooks, hooks.length + 1);
        next[next.length - 1] = hook;
        hooks = next;
        return next.length == 1;
    }

    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
        return new Call(chain, hooks).proceed();
    }

    private static final class Call implements XposedInterface.Chain {
        final XposedInterface.Chain original;
        final XposedInterface.Hooker[] hooks;
        int next;
        boolean receiverChanged;
        Object receiver;
        Object[] args;

        Call(XposedInterface.Chain original, XposedInterface.Hooker[] hooks) {
            this.original = original;
            this.hooks = hooks;
        }

        public Executable getExecutable() { return original.getExecutable(); }
        public Object getThisObject() { return receiverChanged ? receiver : original.getThisObject(); }
        public List<Object> getArgs() { return args == null ? original.getArgs() : Arrays.asList(args); }
        public Object getArg(int index) { return args == null ? original.getArg(index) : args[index]; }

        public Object proceed() throws Throwable {
            int index = next;
            if (index == hooks.length) {
                if (receiverChanged) return args == null ? original.proceedWith(receiver)
                        : original.proceedWith(receiver, args);
                return args == null ? original.proceed() : original.proceed(args);
            }
            next = index + 1;
            try { return hooks[index].intercept(this); }
            finally { next = index; }
        }

        public Object proceed(Object[] replacement) throws Throwable {
            Object[] previous = args;
            args = replacement;
            try { return proceed(); }
            finally { args = previous; }
        }

        public Object proceedWith(Object replacement) throws Throwable {
            Object previous = receiver;
            boolean changed = receiverChanged;
            receiver = replacement;
            receiverChanged = true;
            try { return proceed(); }
            finally { receiver = previous; receiverChanged = changed; }
        }

        public Object proceedWith(Object replacement, Object[] arguments) throws Throwable {
            Object[] previous = args;
            args = arguments;
            try { return proceedWith(replacement); }
            finally { args = previous; }
        }
    }
}
