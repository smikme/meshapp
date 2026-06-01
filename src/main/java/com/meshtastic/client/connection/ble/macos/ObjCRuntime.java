package com.meshtastic.client.connection.ble.macos;

import com.sun.jna.*;
import com.sun.jna.Native;

/**
 * Utilities for working with the Objective-C runtime through JNA.
 * <p>
 * Extends the pattern used by {@code NativeMacOsWindowControl}: besides sending
 * messages with objc_msgSend, it supports class creation and callback-method
 * registration for delegates. CoreBluetooth relies on this delegate pattern.
 * <p>
 * On arm64 Apple Silicon, variadic objc_msgSend must not be called through JNA;
 * every call goes through {@code Function.invoke*()} with fixed argument types.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ObjCRuntime {

    private static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    @SuppressWarnings({"unused", "PMD.UnusedPrivateField"}) // loading framework into process is a required side effect
    private static final NativeLibrary FOUNDATION = NativeLibrary.getInstance(
            "/System/Library/Frameworks/Foundation.framework/Foundation");
    @SuppressWarnings({"unused", "PMD.UnusedPrivateField"}) // loading framework into process is a required side effect
    private static final NativeLibrary CORE_BLUETOOTH = NativeLibrary.getInstance(
            "/System/Library/Frameworks/CoreBluetooth.framework/CoreBluetooth");
    private static final NativeLibrary DISPATCH = NativeLibrary.getInstance(
            "/usr/lib/system/libdispatch.dylib");

    // Objective-C runtime functions
    private static final Function MSG_SEND = OBJC.getFunction("objc_msgSend");
    private static final Function GET_CLASS = OBJC.getFunction("objc_getClass");
    private static final Function SEL_REGISTER = OBJC.getFunction("sel_registerName");
    private static final Function ALLOCATE_CLASS_PAIR = OBJC.getFunction("objc_allocateClassPair");
    private static final Function REGISTER_CLASS_PAIR = OBJC.getFunction("objc_registerClassPair");
    private static final Function ADD_METHOD = OBJC.getFunction("class_addMethod");
    private static final Function ADD_IVAR = OBJC.getFunction("class_addIvar");
    private static final Function GET_IVAR = OBJC.getFunction("object_getInstanceVariable");
    private static final Function SET_IVAR = OBJC.getFunction("object_setInstanceVariable");
    private static final Function GET_PROTOCOL = OBJC.getFunction("objc_getProtocol");
    private static final Function ADD_PROTOCOL = OBJC.getFunction("class_addProtocol");

    // libdispatch
    private static final Function DISPATCH_QUEUE_CREATE = DISPATCH.getFunction("dispatch_queue_create");
    private static final Function DISPATCH_RELEASE = DISPATCH.getFunction("dispatch_release");
    // ====== Class and Selector ======

    /** objc_getClass(name) → Class pointer */
    public static long cls(String name) {
        return GET_CLASS.invokeLong(new Object[]{name});
    }

    /** sel_registerName(name) → SEL */
    public static long sel(String name) {
        return SEL_REGISTER.invokeLong(new Object[]{name});
    }

    // ====== Message Sending (objc_msgSend) ======

    /** objc_msgSend(receiver, sel) → id */
    public static long msgSend(long receiver, String selector) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector)});
    }

    /** objc_msgSend(receiver, sel, id) → id */
    public static long msgSend(long receiver, String selector, long arg) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), arg});
    }

    /** objc_msgSend(receiver, sel, id, id) → id */
    public static long msgSend(long receiver, String selector, long arg1, long arg2) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), arg1, arg2});
    }

    /** objc_msgSend(receiver, sel, id, id, id) → id */
    public static long msgSend(long receiver, String selector, long arg1, long arg2, long arg3) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), arg1, arg2, arg3});
    }

    /** objc_msgSend(receiver, sel, bool) */
    public static void msgSendBool(long receiver, String selector, boolean arg) {
        MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), arg ? 1L : 0L});
    }

    /** [obj retain] for Objective-C objects handed to Java as raw pointers. */
    public static void retain(long obj) {
        if (obj != 0) {
            MSG_SEND.invokeLong(new Object[]{obj, sel("retain")});
        }
    }

    /** [obj release] for Objective-C objects owned by Java-side bridge code. */
    public static void release(long obj) {
        if (obj != 0) {
            MSG_SEND.invokeLong(new Object[]{obj, sel("release")});
        }
    }

    /** objc_msgSend(receiver, sel, Pointer) → id */
    public static long msgSendPtr(long receiver, String selector, Pointer ptr) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), ptr});
    }

    /** objc_msgSend(receiver, sel, Pointer, long) → id */
    public static long msgSendPtrLong(long receiver, String selector, Pointer ptr, long arg) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), ptr, arg});
    }

    // ====== Object Creation ======

    /** [[cls alloc] init] */
    public static long allocInit(String className) {
        long c = cls(className);
        long alloc = msgSend(c, "alloc");
        return msgSend(alloc, "init");
    }

    /** [NSString stringWithUTF8String:javaString] */
    public static long nsString(String javaString) {
        long nsStringClass = cls("NSString");
        long alloc = msgSend(nsStringClass, "alloc");
        return MSG_SEND.invokeLong(new Object[]{alloc, sel("initWithUTF8String:"), javaString});
    }

    /**
     * Extracts a Java String from an NSString pointer.
     * [nsStr UTF8String] -> const char* -> Java String
     */
    public static String toJavaString(long nsStr) {
        if (nsStr == 0) { return null; }
        long cStr = msgSend(nsStr, "UTF8String");
        if (cStr == 0) { return null; }
        return new Pointer(cStr).getString(0);
    }

    // ====== CBUUID ======

    /** [CBUUID UUIDWithString:uuidStr] */
    public static long cbUuid(String uuidStr) {
        long nsStr = nsString(uuidStr);
        try {
            long cbUuidClass = cls("CBUUID");
            return msgSend(cbUuidClass, "UUIDWithString:", nsStr);
        } finally {
            release(nsStr);
        }
    }

    /** [[NSAutoreleasePool alloc] init] for JNA calls made outside Cocoa-managed threads. */
    public static long createAutoreleasePool() {
        return allocInit("NSAutoreleasePool");
    }

    /** [pool drain] for pools created by {@link #createAutoreleasePool()}. */
    public static void drainAutoreleasePool(long pool) {
        if (pool != 0) {
            msgSend(pool, "drain");
        }
    }

    /**
     * Creates an NSArray containing one object: @[obj].
     */
    public static long nsArrayWith(long obj) {
        long nsArrayClass = cls("NSArray");
        return MSG_SEND.invokeLong(new Object[]{
                nsArrayClass, sel("arrayWithObject:"), obj
        });
    }

    // ====== NSData <-> byte[] ======

    /**
     * Creates owned NSData from byte[].
     * [[NSData alloc] initWithBytes:bytes length:len]
     */
    public static long nsData(byte[] bytes) {
        Memory mem = new Memory(bytes.length);
        mem.write(0, bytes, 0, bytes.length);
        long nsDataClass = cls("NSData");
        long alloc = msgSend(nsDataClass, "alloc");
        return MSG_SEND.invokeLong(new Object[]{
                alloc, sel("initWithBytes:length:"), mem, (long) bytes.length
        });
    }

    /**
     * Extracts byte[] from NSData.
     * [nsData bytes] + [nsData length]
     */
    public static byte[] toBytes(long nsData) {
        if (nsData == 0) { return new byte[0]; }
        long length = msgSend(nsData, "length");
        if (length <= 0) { return new byte[0]; }
        long bytesPtr = msgSend(nsData, "bytes");
        if (bytesPtr == 0) { return new byte[0]; }
        byte[] result = new byte[(int) length];
        new Pointer(bytesPtr).read(0, result, 0, result.length);
        return result;
    }

    // ====== Dispatch Queue ======

    /**
     * dispatch_queue_create(label, attr).
     * Creates a serial dispatch queue for CoreBluetooth.
     */
    public static long createDispatchQueue(String label) {
        return DISPATCH_QUEUE_CREATE.invokeLong(new Object[]{label, Pointer.NULL});
    }

    /** Release a queue created by {@link #createDispatchQueue(String)}. */
    public static void releaseDispatchQueue(long queue) {
        if (queue != 0) {
            DISPATCH_RELEASE.invokeVoid(new Object[]{new Pointer(queue)});
        }
    }

    // ====== Delegate Class Creation ======

    /**
     * Creates a new Objective-C class that inherits from NSObject.
     * <pre>
     *   long cls = createClass("MeshBleDelegate", "NSObject");
     *   addMethod(cls, "someCallback:", callback, "v@:@");
     *   registerClass(cls);
     *   long instance = allocInit(cls);
     * </pre>
     *
     * @param name      new class name
     * @param superName superclass name
     * @return pointer to the new, not-yet-registered class
     */
    public static long createClass(String name, String superName) {
        long superCls = cls(superName);
        long newCls = ALLOCATE_CLASS_PAIR.invokeLong(new Object[]{superCls, name, 0L});
        if (newCls == 0) {
            // The class already exists; return the existing class.
            return cls(name);
        }
        return newCls;
    }

    /**
     * Adds an Objective-C protocol to a class.
     */
    public static void addProtocol(long clazz, String protocolName) {
        long protocol = GET_PROTOCOL.invokeLong(new Object[]{protocolName});
        if (protocol != 0) {
            ADD_PROTOCOL.invokeLong(new Object[]{clazz, protocol});
        }
    }

    /**
     * Adds a callback method to a class.
 *
     * @param clazz    class pointer
     * @param selector selector name, for example "centralManagerDidUpdateState:"
     * @param callback JNA Callback implementation
     * @param types    Objective-C type encoding, for example "v@:@" = void(self, _cmd, arg)
     */
    public static void addMethod(long clazz, String selector, Callback callback, String types) {
        long sel = sel(selector);
        ADD_METHOD.invokeLong(new Object[]{clazz, sel, callback, types});
    }

    /**
     * Registers a previously created class. Instances can be created after this call.
     */
    public static void registerClass(long clazz) {
        REGISTER_CLASS_PAIR.invoke(new Object[]{clazz});
    }

    /**
     * Creates an instance from a class pointer: [[cls alloc] init].
     */
    public static long allocInitClass(long clazz) {
        long alloc = MSG_SEND.invokeLong(new Object[]{clazz, sel("alloc")});
        return MSG_SEND.invokeLong(new Object[]{alloc, sel("init")});
    }

    /** Adds an instance variable to an unregistered class. */
    public static void addIvar(long clazz, String name, int size, int alignment, String types) {
        ADD_IVAR.invokeLong(new Object[]{clazz, name, (long) size, (byte) alignment, types});
    }

    /** Reads an ivar value as a pointer. */
    public static long getIvar(long obj, String name) {
        Memory outValue = new Memory(Native.POINTER_SIZE);
        GET_IVAR.invokeLong(new Object[]{obj, name, outValue});
        return outValue.getLong(0);
    }

    /** Sets an ivar value. */
    public static void setIvar(long obj, String name, long value) {
        SET_IVAR.invokeLong(new Object[]{obj, name, value});
    }

    private ObjCRuntime() {}
}
