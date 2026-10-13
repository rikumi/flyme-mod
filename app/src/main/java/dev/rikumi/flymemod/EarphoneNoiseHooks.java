package dev.rikumi.flymemod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Binder;
import android.os.RemoteException;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Protocol and icon paths traced from com.meizu.earphone 100.12.23. */
final class EarphoneNoiseHooks {
    private static final String OWNER="com.flyme.systemui.volume.QSVolumeControllerImpl";
    private static final String SDK="com.meizu.bluetooth.sdk.IMzBluetooth";
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Field iconField,streamField;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final Map<ImageView,WeakReference<Object>> icons=new WeakHashMap<>();
    private final Map<ImageView,Drawable> originals=new WeakHashMap<>();
    private final Map<ImageView,Drawable> replacements=new WeakHashMap<>();
    private final Map<ImageView,Boolean> clickable=new WeakHashMap<>();
    private final Map<ViewGroup,TouchDelegate> touchGestures=new WeakHashMap<>();
    private final EarphoneNoiseState noiseState=new EarphoneNoiseState();
    private final Map<ImageView,Drawable> foregrounds=new WeakHashMap<>();
    private String initializedDevice;
    private long initializationRequestedAt;
    private volatile IBinder appBinder;
    private boolean appBound;
    private volatile IBinder binder;
    private Context context;
    private boolean bound,polling;
    private volatile String deviceId;
    private volatile int mode;
    private int appliedMode;
    private String lastStatus;
    private final Binder sdkCallback=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException{
            String descriptor="com.meizu.bluetooth.sdk.IMzServiceCallBack";
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(descriptor);return true;}
            if(code!=1&&code!=2)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(descriptor);
            status(code==1?"System earphone service connected":"System earphone service disconnected");
            if(reply!=null)reply.writeNoException();
            return true;
        }
    };
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder service){
            if(name.getClassName().endsWith("RemotePrefService"))appBinder=service;else binder=service;
            log.accept("Earphone service connected: " + name.getClassName(),null);
            if(service==binder)worker.post(()->connectSystemService(service));
        }
        public void onServiceDisconnected(ComponentName name){
            if(name.getClassName().endsWith("RemotePrefService"))appBinder=null;else binder=null;
            initializedDevice=null;deviceId=null;mode=0;noiseState.clear();main.post(()->apply(0));
        }
        public void onBindingDied(ComponentName name){onServiceDisconnected(name);release();}
        public void onNullBinding(ComponentName name){onBindingDied(name);}
    };
    private void connectSystemService(IBinder service){
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            // MzBluetoothManager performs this after binding on Meizu devices.
            // Binding the SDK alone leaves its recognized-device map empty.
            data.writeInterfaceToken(SDK);data.writeStrongBinder(sdkCallback);
            if(!service.transact(67,data,reply,0))throw new IllegalStateException("connectMzfpService unavailable");
            reply.readException();status("System earphone service binding: "+(reply.readInt()!=0));
        }catch(Exception error){log.accept("Cannot connect system earphone service",error);}
        finally{data.recycle();reply.recycle();}
    }
    EarphoneNoiseHooks(ClassLoader loader,BooleanSupplier enabled,BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.enabled=enabled;this.log=log;
        Class<?> owner=loader.loadClass(OWNER);
        iconField=owner.getField("mIcon");streamField=owner.getField("mCurrentStreamType");
        owner.getDeclaredMethod("changeIconResource",int.class);
        owner.getDeclaredMethod("setUpIconFrame",int.class,int.class,int.class);
        HandlerThread thread=new HandlerThread("FlymeModEarphone");thread.start();worker=new Handler(thread.getLooper());
    }
    void install(SignalHooks.Installer installer,ClassLoader loader,Consumer<Method> deoptimizer) throws ReflectiveOperationException {
        installer.hook(OWNER,"changeIconResource",chain->{
            ImageView icon=(ImageView)iconField.get(chain.getThisObject());
            Object result=chain.proceed();
            if(icon!=null){
                track(icon,chain.getThisObject());
                if(icon.getDrawable()!=replacements.get(icon))originals.put(icon,icon.getDrawable());
                if(context==null)context=icon.getContext().getApplicationContext();
                if(enabled.getAsBoolean()){
                    start();
                    if(appliedMode!=0)paint(icon,appliedMode);
                }
            }
            return result;
        },int.class);
        installer.hook(OWNER,"setUpIconFrame",chain->{
            ImageView icon=(ImageView)iconField.get(chain.getThisObject());
            if(icon!=null)track(icon,chain.getThisObject());
            if(enabled.getAsBoolean()&&appliedMode!=0&&media(icon)){paint(icon,appliedMode);return null;}
            return chain.proceed();
        },int.class,int.class,int.class);
        installer.hook("android.widget.ImageView","onDraw",chain->{
            if(icons.containsKey(chain.getThisObject())&&enabled.getAsBoolean()&&!polling)start();
            return chain.proceed();
        },android.graphics.Canvas.class);
        installer.hook("android.view.View","performClick",chain->{
            if(chain.getThisObject() instanceof ImageView icon&&icons.containsKey(icon)
                    &&enabled.getAsBoolean()&&deviceId!=null&&mode>=1&&media(icon)){
                String id=deviceId;
                worker.post(()->{
                    try{
                        if(!enabled.getAsBoolean()||!id.equals(deviceId)||binder==null)return;
                        int target=noiseState.target(id,mode);
                        // The app wrapper skips requests matching its stale SDK cache.
                        // Use the same SDK setter directly, after read() has validated the device.
                        if(integer(20,id,target)==0){status("Noise mode request rejected");return;}
                        noiseState.requested(id,target);
                        mode=target;
                        main.post(()->{if(id.equals(deviceId))apply(target);});
                        status("Noise mode requested: "+target);
                        integer(19,id,null);
                    }catch(Exception error){log.accept("Cannot switch earphone noise mode",error);}
                });
                return true;
            }
            return chain.proceed();
        });
        installer.hook("android.view.ViewGroup","dispatchTouchEvent",chain->{
            ViewGroup parent=(ViewGroup)chain.getThisObject();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                touchGestures.remove(parent);
                if(enabled.getAsBoolean()&&deviceId!=null&&mode>=1)
                    for(ImageView icon:clickable.keySet()){
                        if(icon.getParent()!=parent||!icon.isShown()||!media(icon))continue;
                        Rect bounds=new Rect();icon.getHitRect(bounds);
                        float density=icon.getResources().getDisplayMetrics().density;
                        int size=Math.max(Math.round(48*density),Math.max(bounds.width(),bounds.height())+Math.round(16*density));
                        int x=bounds.centerX(),y=bounds.centerY();
                        bounds.set(x-size/2,y-size/2,x+(size+1)/2,y+(size+1)/2);
                        if(bounds.contains((int)event.getX(),(int)event.getY())){
                            touchGestures.put(parent,new TouchDelegate(bounds,icon));
                            break;
                        }
                    }
            }
            TouchDelegate delegate=touchGestures.get(parent);
            if(delegate==null)return chain.proceed();
            MotionEvent copy=MotionEvent.obtain(event);
            try{
                if(!enabled.getAsBoolean()||deviceId==null||mode<1)copy.setAction(MotionEvent.ACTION_CANCEL);
                delegate.onTouchEvent(copy);
            }finally{
                copy.recycle();
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)touchGestures.remove(parent);
            }
            return true;
        },MotionEvent.class);
        // The animation path can inline changeIconResource/setUpIconFrame; keep
        // their actual owner calls observable on precompiled SystemUI builds.
        for(Method method:loader.loadClass(OWNER).getDeclaredMethods())
            if(!java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                    &&!java.lang.reflect.Modifier.isNative(method.getModifiers()))deoptimizer.accept(method);
        log.accept("Earphone noise-control hooks registered",null);
    }
    private void track(ImageView icon,Object owner){
        if(icons.containsKey(icon))return;
        icons.put(icon,new WeakReference<>(owner));
        originals.put(icon,icon.getDrawable());
        if(context==null)context=icon.getContext().getApplicationContext();
        icon.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){if(enabled.getAsBoolean())start();}
            public void onViewDetachedFromWindow(View view){}
        });
        icon.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(enabled.getAsBoolean()&&v.isShown()&&!polling)start();
        });
        if(enabled.getAsBoolean())start();
    }
    void refresh(){
        if(context==null)return;
        if(enabled.getAsBoolean())start();
        else{deviceId=null;mode=0;apply(0);release();}
    }
    private void start(){
        if(!bound){
            try{
                Intent intent=new Intent().setComponent(new ComponentName("com.meizu.earphone","com.meizu.bluetooth.sdk.MzBluetoothService"));
                bound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
                if(!bound)status("SDK service binding returned false");
            }catch(RuntimeException error){log.accept("Cannot bind Meizu earphone service",error);}
        }
        if(!appBound){
            try{
                Intent intent=new Intent("com.meizu.earphone.ACTION_REMOTE_BIND").setComponent(
                        new ComponentName("com.meizu.earphone","com.meizu.earphone.biz.devicecontrol.RemotePrefService"));
                appBound=context.bindService(intent,connection,Context.BIND_AUTO_CREATE);
                if(!appBound)status("App control service binding returned false");
            }catch(RuntimeException error){log.accept("Cannot bind earphone app controls",error);}
        }
        if(!polling){polling=true;main.post(tick);}
    }
    private void release(){
        if(bound){try{context.unbindService(connection);}catch(RuntimeException ignored){}bound=false;}
        if(appBound){try{context.unbindService(connection);}catch(RuntimeException ignored){}appBound=false;}
        binder=null;appBinder=null;initializedDevice=null;noiseState.clear();
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!enabled.getAsBoolean()){polling=false;return;}
        boolean visible=false;
        for(ImageView icon:icons.keySet())if(icon.isShown()&&media(icon)){visible=true;break;}
        if(!visible){polling=false;deviceId=null;mode=0;apply(0);return;}
        worker.post(()->{
            try{read();}catch(Exception error){
                status("State query failed: "+error.getClass().getSimpleName()+": "+error.getMessage());
                deviceId=null;mode=0;main.post(()->apply(0));
            }
            main.postDelayed(this,1500);
        });
    }};
    private void read() throws Exception {
        IBinder service=binder;
        String id=null;int current=0;
        AudioManager audio=context.getSystemService(AudioManager.class);
        if(service!=null&&audio!=null){
            // The SDK resolves only recognized earphones; do not guess by Bluetooth name.
            for(AudioDeviceInfo output:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
                if(output.getType()!=AudioDeviceInfo.TYPE_BLUETOOTH_A2DP&&output.getType()!=AudioDeviceInfo.TYPE_BLE_HEADSET)continue;
                String candidate=string(54,output.getAddress());
                if(candidate==null||candidate.isEmpty())continue;
                if(appBinder==null)continue;
                if(!candidate.equals(initializedDevice)){
                    initializeDevice(candidate);
                    continue;
                }
                if(integer(62,candidate,null)==0){status("Recognized earphone is not connected to SDK");continue;}
                Parcel appDevice=appRequest(8,candidate,null);
                boolean recognized;
                try{
                    recognized=appDevice.readInt()!=0&&candidate.equals(appDevice.readString());
                    if(recognized){String appModel=appDevice.readString();recognized=appModel!=null&&!appModel.isEmpty();}
                }finally{appDevice.recycle();}
                if(!recognized){
                    status("Earphone app has not accepted the device");
                    // initBtSdk posts prepare() + directConnectDevice() to the app's main thread.
                    // Its own SDK binding is asynchronous, so the first request may create no
                    // cached device. A successful Binder transaction is not device acceptance.
                    if(android.os.SystemClock.uptimeMillis()-initializationRequestedAt>=5000)
                        initializeDevice(candidate);
                    continue;
                }
                if(integer(63,candidate,null)==0){status("Waiting for earphone protocol");integer(7,candidate,null);continue;}
                integer(19,candidate,null);
                String model=string(56,candidate);
                if(model==null||model.isEmpty())continue;
                int value=integer(18,candidate,null);
                if(value>=1&&value<=3){id=candidate;current=noiseState.observed(candidate,value);status("Noise mode available: "+value);break;}
                status("Unsupported or pending noise mode: "+value);
            }
        }
        if(id==null)noiseState.clear();
        deviceId=id;mode=current;
        int next=current;main.post(()->apply(next));
    }
    private void initializeDevice(String id) throws Exception {
        Parcel reply=appRequest(4,id,null);
        reply.recycle();
        initializedDevice=id;
        initializationRequestedAt=android.os.SystemClock.uptimeMillis();
        status("Earphone app initialization requested");
    }
    private synchronized void status(String message){
        if(message.equals(lastStatus))return;
        lastStatus=message;log.accept("Earphone noise control: "+message,null);
    }
    private Parcel appRequest(int code,String id,Integer value) throws Exception {
        return request(appBinder,"com.meizu.earphone.biz.devicecontrol.remote.IRemotePrefService",code,id,value);
    }
    private Parcel request(int code,String id,Integer value) throws Exception {
        return request(binder,SDK,code,id,value);
    }
    private Parcel request(IBinder service,String descriptor,int code,String id,Integer value) throws Exception {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(descriptor);data.writeString(id);if(value!=null)data.writeInt(value);
            if(service==null||!service.transact(code,data,reply,0))throw new IllegalStateException("Earphone SDK unavailable");
            reply.readException();return reply;
        }catch(Exception error){reply.recycle();throw error;}finally{data.recycle();}
    }
    private int integer(int code,String id,Integer value) throws Exception{Parcel reply=request(code,id,value);try{return reply.readInt();}finally{reply.recycle();}}
    private String string(int code,String id) throws Exception{Parcel reply=request(code,id,null);try{return reply.readString();}finally{reply.recycle();}}
    private boolean media(ImageView icon){
        try{
            Object owner=icons.get(icon).get();
            if(owner==null||streamField.getInt(owner)!=AudioManager.STREAM_MUSIC)return false;
            AudioManager audio=icon.getContext().getSystemService(AudioManager.class);
            int devices=(Integer)AudioManager.class.getMethod("getDevicesForStream",int.class).invoke(audio,AudioManager.STREAM_MUSIC);
            return (devices&(0x80|0x100|0x200|0x20000000))!=0;
        }catch(ReflectiveOperationException|RuntimeException error){return false;}
    }
    private void apply(int value){
        if(!enabled.getAsBoolean())value=0;
        appliedMode=value;
        for(ImageView icon:icons.keySet()){
            if(value!=0&&media(icon))paint(icon,value);
            else if(clickable.containsKey(icon)){
                icon.setImageDrawable(originals.get(icon));icon.setClickable(clickable.remove(icon));
                replacements.remove(icon);
                icon.setForeground(foregrounds.remove(icon));
            }
        }
    }
    private void paint(ImageView icon,int value){
        if(!media(icon))return;
        try{
            Context app=icon.getContext().createPackageContext("com.meizu.earphone",0);
            String name=value==3?"dc_icon_anc_transparent_normal":value==2?"dc_icon_anc_on_normal":"dc_icon_anc_off_normal";
            int res=app.getResources().getIdentifier(name,"drawable","com.meizu.earphone");
            if(res==0)return;
            if(!clickable.containsKey(icon)){
                clickable.put(icon,icon.isClickable());
                foregrounds.put(icon,icon.getForeground());
                icon.setForeground(EarphoneNoiseRipple.create());
            }
            icon.setClickable(true);
            icon.getClass().getMethod("cancelAnimation").invoke(icon);
            Drawable replacement=app.getDrawable(res);
            replacements.put(icon,replacement);icon.setImageDrawable(replacement);
        }catch(Exception error){log.accept("Cannot load Meizu noise-control icon",error);}
    }
}
