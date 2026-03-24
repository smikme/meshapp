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
    if (@available(macOS 11.0, *)) {
        NSImage *symbol = [NSImage imageWithSystemSymbolName:@"antenna.radiowaves.left.and.right"
                                      accessibilityDescription:@"MeshApp"];
        if (symbol != nil) {
            symbol.template = YES;
            return symbol;
        }
    }

    if (iconPath != nil && iconPath.length > 0) {
        NSImage *image = [[NSImage alloc] initWithContentsOfFile:iconPath];
        if (image != nil) {
            image.template = YES;
            [image setSize:NSMakeSize(18.0, 18.0)];
            return image;
        }
    }

    return nil;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJvm = vm;
    return JNI_VERSION_1_8;
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
