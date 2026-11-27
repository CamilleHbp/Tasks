#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#import <AppKit/AppKit.h>
#include <jni.h>

@interface VerveNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation VerveNotificationDelegate
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
 didReceiveNotificationResponse:(UNNotificationResponse *)response
          withCompletionHandler:(void (^)(void))completionHandler {
    NSString *task = response.notification.request.content.userInfo[@"taskId"];
    if ([task isKindOfClass:NSString.class] && task.length > 0) {
        NSURLComponents *url = [NSURLComponents componentsWithString:@"vervetwodo-preview://task"];
        url.queryItems = @[[NSURLQueryItem queryItemWithName:@"id" value:task]];
        dispatch_async(dispatch_get_main_queue(), ^{ [NSWorkspace.sharedWorkspace openURL:url.URL]; });
    }
    completionHandler();
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
      willPresentNotification:(UNNotification *)notification
        withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
    // Request normal system presentation even while the task window is active.
    completionHandler(UNNotificationPresentationOptionBanner |
                      UNNotificationPresentationOptionList |
                      UNNotificationPresentationOptionSound);
    NSLog(@"VerveTwoDo: requested foreground notification banner and sound");
}

@end

// UNUserNotificationCenter keeps a weak delegate reference. Retain it for the app's lifetime.
static VerveNotificationDelegate *notificationDelegate;

static void fail(JNIEnv *env, NSString *message) {
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception != NULL) (*env)->ThrowNew(env, exception, message.UTF8String);
}

static UNUserNotificationCenter *install(JNIEnv *env) {
    if (![NSBundle.mainBundle.bundleIdentifier isEqualToString:@"studio.camille.vervetwodo.preview"]) {
        fail(env, @"Notifications require the packaged application");
        return nil;
    }
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        notificationDelegate = [VerveNotificationDelegate new];
    });
    UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
    center.delegate = notificationDelegate;
    return center;
}

static NSString *javaString(JNIEnv *env, jstring value) {
    const jchar *characters = (*env)->GetStringChars(env, value, NULL);
    if (characters == NULL) return nil;
    NSString *string = [[NSString alloc] initWithCharacters:characters length:(NSUInteger)(*env)->GetStringLength(env, value)];
    (*env)->ReleaseStringChars(env, value, characters);
    return string;
}

JNIEXPORT void JNICALL Java_studio_camille_vervetwodo_desktop_MacNotifications_installNative(JNIEnv *env, jobject self) {
    @autoreleasepool { install(env); }
}

JNIEXPORT void JNICALL Java_studio_camille_vervetwodo_desktop_MacNotifications_scheduleNative(
    JNIEnv *env, jobject self, jstring identifier, jstring title, jstring body) {
    @autoreleasepool {
        // Called on Dispatchers.IO; never block the Cocoa event loop waiting for authorization.
        if (NSThread.isMainThread) { fail(env, @"Notification scheduling requires a background thread"); return; }
        UNUserNotificationCenter *center = install(env);
        if (center == nil) return;
        NSString *requestID = javaString(env, identifier);
        NSString *requestTitle = javaString(env, title);
        NSString *requestBody = javaString(env, body);
        if ((*env)->ExceptionCheck(env)) return;

        dispatch_semaphore_t permission = dispatch_semaphore_create(0);
        __block BOOL allowed = NO;
        __block NSError *permissionError = nil;
        [center requestAuthorizationWithOptions:UNAuthorizationOptionAlert | UNAuthorizationOptionSound
                              completionHandler:^(BOOL granted, NSError *error) {
            allowed = granted;
            permissionError = error;
            dispatch_semaphore_signal(permission);
        }];
        if (dispatch_semaphore_wait(permission, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC)) != 0) {
            fail(env, @"Notification authorization timed out"); return;
        }
        if (!allowed || permissionError != nil) {
            fail(env, permissionError.localizedDescription ?: @"Notification permission denied"); return;
        }

        UNMutableNotificationContent *content = [UNMutableNotificationContent new];
        content.title = requestTitle;
        content.body = requestBody;
        content.sound = UNNotificationSound.defaultSound;
        UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:requestID content:content
            trigger:[UNTimeIntervalNotificationTrigger triggerWithTimeInterval:2 repeats:NO]];
        dispatch_semaphore_t scheduled = dispatch_semaphore_create(0);
        __block NSError *schedulingError = nil;
        [center addNotificationRequest:request withCompletionHandler:^(NSError *error) {
            schedulingError = error;
            dispatch_semaphore_signal(scheduled);
        }];
        if (dispatch_semaphore_wait(scheduled, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
            fail(env, @"Notification scheduling timed out"); return;
        }
        if (schedulingError != nil) fail(env, schedulingError.localizedDescription);
    }
}

JNIEXPORT void JNICALL Java_studio_camille_vervetwodo_desktop_MacNotifications_reconcileNative(
    JNIEnv *env, jobject self, jstring payload) {
    @autoreleasepool {
        if (NSThread.isMainThread) { fail(env, @"Notification scheduling requires a background thread"); return; }
        UNUserNotificationCenter *center = install(env);
        if (center == nil) return;
        NSString *json = javaString(env, payload);
        if (json == nil) return;
        NSError *parseError = nil;
        NSArray *desired = [NSJSONSerialization JSONObjectWithData:[json dataUsingEncoding:NSUTF8StringEncoding] options:0 error:&parseError];
        if (![desired isKindOfClass:NSArray.class] || parseError != nil) { fail(env, @"Invalid reminder request"); return; }
        if (desired.count > 0) {
            dispatch_semaphore_t permission = dispatch_semaphore_create(0);
            __block BOOL allowed = NO;
            [center requestAuthorizationWithOptions:UNAuthorizationOptionAlert | UNAuthorizationOptionSound completionHandler:^(BOOL granted, NSError *error) {
                allowed = granted && error == nil;
                dispatch_semaphore_signal(permission);
            }];
            if (dispatch_semaphore_wait(permission, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC)) != 0 || !allowed) {
                fail(env, @"Notification permission denied"); return;
            }
        }
        dispatch_semaphore_t fetched = dispatch_semaphore_create(0);
        __block NSArray<UNNotificationRequest *> *pending;
        [center getPendingNotificationRequestsWithCompletionHandler:^(NSArray<UNNotificationRequest *> *requests) {
            pending = requests; dispatch_semaphore_signal(fetched);
        }];
        if (dispatch_semaphore_wait(fetched, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
            fail(env, @"Pending reminder lookup timed out"); return;
        }
        NSMutableDictionary *wanted = [NSMutableDictionary dictionary];
        for (NSDictionary *item in desired) wanted[item[@"id"]] = item;
        NSMutableSet *unchanged = [NSMutableSet set];
        NSMutableArray *removed = [NSMutableArray array];
        for (UNNotificationRequest *request in pending) {
            if (![request.identifier hasPrefix:@"verve-task-"]) continue;
            NSDictionary *item = wanted[request.identifier];
            if (item == nil) [removed addObject:request.identifier];
            else if ([request.content.title isEqualToString:item[@"title"]] && [request.content.body isEqualToString:item[@"body"]]) [unchanged addObject:request.identifier];
        }
        [center removePendingNotificationRequestsWithIdentifiers:removed];
        for (NSDictionary *item in desired) {
            if ([unchanged containsObject:item[@"id"]]) continue;
            NSTimeInterval delay = [item[@"epochSeconds"] doubleValue] - NSDate.date.timeIntervalSince1970;
            if (delay <= 0) continue;
            UNMutableNotificationContent *content = [UNMutableNotificationContent new];
            content.title = item[@"title"]; content.body = item[@"body"]; content.sound = UNNotificationSound.defaultSound;
            content.userInfo = @{@"taskId": item[@"taskId"]};
            UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:item[@"id"] content:content
                trigger:[UNTimeIntervalNotificationTrigger triggerWithTimeInterval:MAX(1, delay) repeats:NO]];
            dispatch_semaphore_t scheduled = dispatch_semaphore_create(0);
            __block NSError *error;
            [center addNotificationRequest:request withCompletionHandler:^(NSError *value) { error = value; dispatch_semaphore_signal(scheduled); }];
            if (dispatch_semaphore_wait(scheduled, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0 || error != nil) {
                fail(env, error.localizedDescription ?: @"Reminder scheduling timed out"); return;
            }
        }
    }
}
