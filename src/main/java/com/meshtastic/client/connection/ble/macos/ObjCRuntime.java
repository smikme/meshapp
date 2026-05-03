package com.meshtastic.client.connection.ble.macos;

import com.sun.jna.*;
import com.sun.jna.Native;

/**
 * Утилиты для работы с Objective-C runtime через JNA.
 * <p>
 * Расширяет паттерн из {@code NativeMacOsWindowControl}: помимо отправки сообщений
 * (objc_msgSend), поддерживает создание классов и регистрацию callback-методов
 * (делегатов) — необходимо для CoreBluetooth, который использует delegate-паттерн.
 * <p>
 * На arm64 (Apple Silicon) нельзя использовать variadic objc_msgSend через JNA —
 * все вызовы используют {@code Function.invoke*()} с фиксированными типами.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ObjCRuntime {

    private static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    @SuppressWarnings("PMD.UnusedPrivateField") // loading framework into process is a required side effect
    private static final NativeLibrary FOUNDATION = NativeLibrary.getInstance(
            "/System/Library/Frameworks/Foundation.framework/Foundation");
    @SuppressWarnings("PMD.UnusedPrivateField")
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
    // ====== Класс и селектор ======

    /** objc_getClass(name) → Class pointer */
    public static long cls(String name) {
        return GET_CLASS.invokeLong(new Object[]{name});
    }

    /** sel_registerName(name) → SEL */
    public static long sel(String name) {
        return SEL_REGISTER.invokeLong(new Object[]{name});
    }

    // ====== Отправка сообщений (objc_msgSend) ======

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

    /** objc_msgSend(receiver, sel, Pointer) → id */
    public static long msgSendPtr(long receiver, String selector, Pointer ptr) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), ptr});
    }

    /** objc_msgSend(receiver, sel, Pointer, long) → id */
    public static long msgSendPtrLong(long receiver, String selector, Pointer ptr, long arg) {
        return MSG_SEND.invokeLong(new Object[]{receiver, sel(selector), ptr, arg});
    }

    // ====== Создание объектов ======

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
     * Извлекает Java String из NSString pointer.
     * [nsStr UTF8String] → const char* → Java String
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
        long cbUuidClass = cls("CBUUID");
        return msgSend(cbUuidClass, "UUIDWithString:", nsStr);
    }

    /**
     * Создаёт NSArray из одного объекта: @[obj]
     */
    public static long nsArrayWith(long obj) {
        long nsArrayClass = cls("NSArray");
        return MSG_SEND.invokeLong(new Object[]{
                nsArrayClass, sel("arrayWithObject:"), obj
        });
    }

    // ====== NSData ↔ byte[] ======

    /**
     * Создаёт NSData из byte[].
     * [NSData dataWithBytes:bytes length:len]
     */
    public static long nsData(byte[] bytes) {
        Memory mem = new Memory(bytes.length);
        mem.write(0, bytes, 0, bytes.length);
        long nsDataClass = cls("NSData");
        return MSG_SEND.invokeLong(new Object[]{
                nsDataClass, sel("dataWithBytes:length:"), mem, (long) bytes.length
        });
    }

    /**
     * Извлекает byte[] из NSData.
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
     * dispatch_queue_create(label, attr)
     * Создаёт serial dispatch queue для CoreBluetooth.
     */
    public static long createDispatchQueue(String label) {
        return DISPATCH_QUEUE_CREATE.invokeLong(new Object[]{label, Pointer.NULL});
    }

    // ====== Создание делегат-классов ======

    /**
     * Создаёт новый Objective-C класс, наследующий от NSObject.
     * <pre>
     *   long cls = createClass("MeshBleDelegate", "NSObject");
     *   addMethod(cls, "someCallback:", callback, "v@:@");
     *   registerClass(cls);
     *   long instance = allocInit(cls);
     * </pre>
     *
     * @param name      имя нового класса
     * @param superName имя суперкласса
     * @return указатель на новый (незарегистрированный) класс
     */
    public static long createClass(String name, String superName) {
        long superCls = cls(superName);
        long newCls = ALLOCATE_CLASS_PAIR.invokeLong(new Object[]{superCls, name, 0L});
        if (newCls == 0) {
            // Класс уже существует — вернуть существующий
            return cls(name);
        }
        return newCls;
    }

    /**
     * Добавляет Objective-C протокол к классу.
     */
    public static void addProtocol(long clazz, String protocolName) {
        long protocol = GET_PROTOCOL.invokeLong(new Object[]{protocolName});
        if (protocol != 0) {
            ADD_PROTOCOL.invokeLong(new Object[]{clazz, protocol});
        }
    }

    /**
     * Добавляет метод (callback) к классу.
     *
     * @param clazz    указатель на класс
     * @param selector имя селектора (напр. "centralManagerDidUpdateState:")
     * @param callback JNA Callback-реализация
     * @param types    Objective-C type encoding (напр. "v@:@" = void(self, _cmd, arg))
     */
    public static void addMethod(long clazz, String selector, Callback callback, String types) {
        long sel = sel(selector);
        ADD_METHOD.invokeLong(new Object[]{clazz, sel, callback, types});
    }

    /**
     * Регистрирует ранее созданный класс. После вызова можно создавать экземпляры.
     */
    public static void registerClass(long clazz) {
        REGISTER_CLASS_PAIR.invoke(new Object[]{clazz});
    }

    /**
     * Создаёт экземпляр класса по его pointer: [[cls alloc] init]
     */
    public static long allocInitClass(long clazz) {
        long alloc = MSG_SEND.invokeLong(new Object[]{clazz, sel("alloc")});
        return MSG_SEND.invokeLong(new Object[]{alloc, sel("init")});
    }

    /** Добавляет instance variable к незарегистрированному классу. */
    public static void addIvar(long clazz, String name, int size, int alignment, String types) {
        ADD_IVAR.invokeLong(new Object[]{clazz, name, (long) size, (byte) alignment, types});
    }

    /** Получает значение ivar как pointer. */
    public static long getIvar(long obj, String name) {
        Memory outValue = new Memory(Native.POINTER_SIZE);
        GET_IVAR.invokeLong(new Object[]{obj, name, outValue});
        return outValue.getLong(0);
    }

    /** Устанавливает значение ivar. */
    public static void setIvar(long obj, String name, long value) {
        SET_IVAR.invokeLong(new Object[]{obj, name, value});
    }

    private ObjCRuntime() {}
}
