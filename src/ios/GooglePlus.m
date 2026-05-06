#import "AppDelegate.h"
#import "objc/runtime.h"
#import "GooglePlus.h"

@implementation GooglePlus

- (void)pluginInitialize
{
    NSLog(@"GooglePlus pluginInitizalize");
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleOpenURL:) name:CDVPluginHandleOpenURLNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleOpenURLWithAppSourceAndAnnotation:) name:CDVPluginHandleOpenURLWithAppSourceAndAnnotationNotification object:nil];
}

- (void)handleOpenURL:(NSNotification*)notification
{
    // no need to handle this handler, we dont have an sourceApplication here, which is required by GIDSignIn handleURL
}

- (void)handleOpenURLWithAppSourceAndAnnotation:(NSNotification*)notification
{
    NSMutableDictionary * options = [notification object];

    NSURL* url = options[@"url"];

    NSString* possibleReversedClientId = [url.absoluteString componentsSeparatedByString:@":"].firstObject;

    if ([possibleReversedClientId isEqualToString:self.getreversedClientId] && self.isSigningIn) {
        self.isSigningIn = NO;
        [[GIDSignIn sharedInstance] handleURL:url];
    }
}

- (void) isAvailable:(CDVInvokedUrlCommand*)command {
  CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:YES];
  [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) login:(CDVInvokedUrlCommand*)command {
    _callbackId = command.callbackId;
    NSDictionary* options = command.arguments[0];

    GIDConfiguration *config = [self buildConfigFromOptions:options];
    if (config == nil) return;

    NSString* scopesString = options[@"scopes"];
    NSArray* additionalScopes = nil;
    if (scopesString != nil) {
        additionalScopes = [scopesString componentsSeparatedByString:@" "];
    }

    NSString *loginHint = options[@"loginHint"];

    self.isSigningIn = YES;
    [GIDSignIn.sharedInstance setConfiguration:config];
    [GIDSignIn.sharedInstance signInWithPresentingViewController:self.viewController
                                                           hint:loginHint
                                               additionalScopes:additionalScopes
                                                     completion:^(GIDSignInResult *signInResult, NSError *error) {
        self.isSigningIn = NO;
        if (error) {
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        } else {
            NSDictionary *result = [self buildResultFromUser:signInResult.user serverAuthCode:signInResult.serverAuthCode];
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        }
    }];
}

- (void) trySilentLogin:(CDVInvokedUrlCommand*)command {
    _callbackId = command.callbackId;
    NSDictionary* options = command.arguments[0];

    GIDConfiguration *config = [self buildConfigFromOptions:options];
    if (config == nil) return;

    [GIDSignIn.sharedInstance setConfiguration:config];
    [GIDSignIn.sharedInstance restorePreviousSignInWithCompletion:^(GIDGoogleUser *user, NSError *error) {
        if (error) {
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        } else {
            NSDictionary *result = [self buildResultFromUser:user serverAuthCode:nil];
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self->_callbackId];
        }
    }];
}

- (GIDConfiguration*) buildConfigFromOptions:(NSDictionary*)options {
    NSString *reversedClientId = [self getreversedClientId];

    if (reversedClientId == nil) {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Could not find REVERSED_CLIENT_ID url scheme in app .plist"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:_callbackId];
        return nil;
    }

    NSString *clientId = [self reverseUrlScheme:reversedClientId];
    NSString *serverClientId = options[@"webClientId"];
    NSString *hostedDomain = options[@"hostedDomain"];
    BOOL offline = [options[@"offline"] boolValue];

    NSString *effectiveServerClientId = (serverClientId != nil && offline) ? serverClientId : nil;

    return [[GIDConfiguration alloc] initWithClientID:clientId
                                       serverClientID:effectiveServerClientId
                                         hostedDomain:hostedDomain
                                          openIDRealm:nil];
}

- (NSDictionary*) buildResultFromUser:(GIDGoogleUser*)user serverAuthCode:(NSString*)serverAuthCode {
    NSString *email = user.profile.email;
    NSString *idToken = user.idToken.tokenString;
    NSString *accessToken = user.accessToken.tokenString;
    NSString *refreshToken = user.refreshToken.tokenString;
    NSString *userId = user.userID;
    NSURL *imageUrl = [user.profile imageURLWithDimension:120];
    return @{
        @"email"          : email           ? : [NSNull null],
        @"idToken"        : idToken         ? : [NSNull null],
        @"serverAuthCode" : serverAuthCode  ? : @"",
        @"accessToken"    : accessToken     ? : [NSNull null],
        @"refreshToken"   : refreshToken    ? : [NSNull null],
        @"userId"         : userId          ? : [NSNull null],
        @"displayName"    : user.profile.name       ? : [NSNull null],
        @"givenName"      : user.profile.givenName  ? : [NSNull null],
        @"familyName"     : user.profile.familyName ? : [NSNull null],
        @"imageUrl"       : imageUrl ? imageUrl.absoluteString : [NSNull null],
    };
}

- (NSString*) reverseUrlScheme:(NSString*)scheme {
  NSArray* originalArray = [scheme componentsSeparatedByString:@"."];
  NSArray* reversedArray = [[originalArray reverseObjectEnumerator] allObjects];
  NSString* reversedString = [reversedArray componentsJoinedByString:@"."];
  return reversedString;
}

- (NSString*) getreversedClientId {
  NSArray* URLTypes = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleURLTypes"];

  if (URLTypes != nil) {
    for (NSDictionary* dict in URLTypes) {
      NSString *urlName = dict[@"CFBundleURLName"];
      if ([urlName isEqualToString:@"REVERSED_CLIENT_ID"]) {
        NSArray* URLSchemes = dict[@"CFBundleURLSchemes"];
        if (URLSchemes != nil) {
          return URLSchemes[0];
        }
      }
    }
  }
  return nil;
}

- (void) logout:(CDVInvokedUrlCommand*)command {
  [GIDSignIn.sharedInstance signOut];
  CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"logged out"];
  [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) disconnect:(CDVInvokedUrlCommand*)command {
  [GIDSignIn.sharedInstance disconnectWithCompletion:^(NSError *error) {
      CDVPluginResult *pluginResult;
      if (error) {
          pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
      } else {
          pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"disconnected"];
      }
      [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
  }];
}

- (void) share_unused:(CDVInvokedUrlCommand*)command {
  // for a rainy day..
}

@end
