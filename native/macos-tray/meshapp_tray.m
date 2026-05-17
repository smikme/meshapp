#import <AppKit/AppKit.h>
#include <dispatch/dispatch.h>
#include <jni.h>
#include <stdbool.h>

static JavaVM *gJvm = NULL;
static jclass gBridgeClass = NULL;
static jmethodID gHandleClickMethod = NULL;
static NSStatusItem *gStatusItem = nil;

@interface MeshAppTrayTarget : NSObject
- (void)handlePrimaryClick:(id)sender;
@end

static void meshapp_tray_invoke_java_click(void) {
    if (gJvm == NULL || gBridgeClass == NULL || gHandleClickMethod == NULL) {
        return;
    }

    JNIEnv *env = NULL;
    bool didAttach = false;
    jint envStatus = (*gJvm)->GetEnv(gJvm, (void **)&env, JNI_VERSION_1_8);
    if (envStatus == JNI_EDETACHED) {
        if ((*gJvm)->AttachCurrentThread(gJvm, (void **)&env, NULL) != JNI_OK) {
            return;
        }
        didAttach = true;
    } else if (envStatus != JNI_OK) {
        return;
    }

    (*env)->CallStaticVoidMethod(env, gBridgeClass, gHandleClickMethod);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    if (didAttach) {
        (*gJvm)->DetachCurrentThread(gJvm);
    }
}
@implementation MeshAppTrayTarget
- (void)handlePrimaryClick:(id)sender {
    (void)sender;
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        @autoreleasepool {
            meshapp_tray_invoke_java_click();
        }
    });
}
@end

static MeshAppTrayTarget *gTrayTarget = nil;

static void meshapp_tray_release_java_refs(JNIEnv *env) {
    if (env != NULL && gBridgeClass != NULL) {
        (*env)->DeleteGlobalRef(env, gBridgeClass);
        gBridgeClass = NULL;
    }
    gHandleClickMethod = NULL;
}

static void meshapp_tray_run_on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
        return;
    }
    dispatch_sync(dispatch_get_main_queue(), block);
}

static void meshapp_tray_run_on_main_async(dispatch_block_t block) {
    dispatch_async(dispatch_get_main_queue(), block);
}

static NSString *meshapp_tray_to_ns_string(JNIEnv *env, jstring value) {
    if (value == NULL) {
        return nil;
    }

    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) {
        return nil;
    }

    NSString *result = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return result;
}

static NSImage *meshapp_tray_load_image(NSString *iconPath) {
    if (iconPath != nil && iconPath.length > 0) {
        NSImage *image = [[NSImage alloc] initWithContentsOfFile:iconPath];
        if (image != nil) {
            image.template = YES;
            [image setSize:NSMakeSize(18.0, 18.0)];
            return image;
        }
    }

    if (@available(macOS 11.0, *)) {
        NSImage *symbol = [NSImage imageWithSystemSymbolName:@"antenna.radiowaves.left.and.right"
                                      accessibilityDescription:@"MeshApp"];
        if (symbol != nil) {
            symbol.template = YES;
            return symbol;
        }
    }

    return nil;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) == JNI_OK) {
        meshapp_tray_release_java_refs(env);
    }
    gJvm = NULL;
}

JNIEXPORT jboolean JNICALL Java_com_meshtastic_client_tray_MacOsTrayBridge_install0(
        JNIEnv *env,
        jclass clazz,
        jstring iconPath,
        jstring toolTip
) {
    if (gBridgeClass == NULL) {
        gBridgeClass = (*env)->NewGlobalRef(env, clazz);
        if (gBridgeClass == NULL) {
            return JNI_FALSE;
        }
    }

    if (gHandleClickMethod == NULL) {
        gHandleClickMethod = (*env)->GetStaticMethodID(env, clazz, "handleClickFromNative", "()V");
        if (gHandleClickMethod == NULL) {
            meshapp_tray_release_java_refs(env);
            return JNI_FALSE;
        }
    }

    NSString *iconPathString = meshapp_tray_to_ns_string(env, iconPath);
    NSString *toolTipString = meshapp_tray_to_ns_string(env, toolTip);

    __block BOOL installed = NO;
    meshapp_tray_run_on_main(^{
        [NSApplication sharedApplication];

        if (gStatusItem != nil) {
            installed = YES;
            return;
        }

        gStatusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSSquareStatusItemLength];
        if (gStatusItem == nil) {
            return;
        }

        NSStatusBarButton *button = gStatusItem.button;
        if (button == nil) {
            [[NSStatusBar systemStatusBar] removeStatusItem:gStatusItem];
            gStatusItem = nil;
            return;
        }

        NSImage *image = meshapp_tray_load_image(iconPathString);
        if (image != nil) {
            button.image = image;
        } else {
            button.title = @"Mesh";
        }

        if (toolTipString != nil && toolTipString.length > 0) {
            button.toolTip = toolTipString;
        }

        if (gTrayTarget == nil) {
            gTrayTarget = [MeshAppTrayTarget new];
        }
        button.target = gTrayTarget;
        button.action = @selector(handlePrimaryClick:);
        installed = YES;
    });

    return installed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_meshtastic_client_tray_MacOsTrayBridge_dispose0(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    meshapp_tray_run_on_main(^{
        if (gStatusItem != nil) {
            [[NSStatusBar systemStatusBar] removeStatusItem:gStatusItem];
            gStatusItem = nil;
        }
        gTrayTarget = nil;
    });
}

JNIEXPORT void JNICALL Java_com_meshtastic_client_tray_MacOsTrayBridge_activate0(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    meshapp_tray_run_on_main_async(^{
        [NSApplication sharedApplication];
        [NSApp activateIgnoringOtherApps:YES];
    });
}

JNIEXPORT jboolean JNICALL Java_com_meshtastic_client_tray_MacOsTrayBridge_focusWindow0(
        JNIEnv *env,
        jclass clazz,
        jlong nsWindowPtr,
        jlong nsViewPtr
) {
    (void)env;
    (void)clazz;

    if (nsWindowPtr == 0) {
        return JNI_FALSE;
    }

    __block BOOL focused = NO;
    meshapp_tray_run_on_main(^{
        [NSApplication sharedApplication];
        [NSApp activateIgnoringOtherApps:YES];

        NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
        NSView *view = nsViewPtr != 0 ? (__bridge NSView *)(void *)nsViewPtr : nil;
        if (window == nil) {
            return;
        }

        [window orderFrontRegardless];
        [window makeKeyAndOrderFront:nil];
        [window makeMainWindow];

        if (view != nil && [view acceptsFirstResponder]) {
            focused = [window makeFirstResponder:view];
        }

        if (!focused) {
            NSView *contentView = window.contentView;
            if (contentView != nil && [contentView acceptsFirstResponder]) {
                focused = [window makeFirstResponder:contentView];
            }
        }
    });

    return focused ? JNI_TRUE : JNI_FALSE;
}
