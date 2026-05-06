package nl.xservices.plugins;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.apache.cordova.*;
import org.apache.cordova.engine.SystemWebChromeClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import android.content.pm.Signature;

/**
 * Originally written by Eddy Verbruggen (http://github.com/EddyVerbruggen/cordova-plugin-googleplus)
 * Forked/Duplicated and Modified by PointSource, LLC, 2016.
 */
public class GooglePlus extends CordovaPlugin {

    public static final String ACTION_IS_AVAILABLE = "isAvailable";
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_TRY_SILENT_LOGIN = "trySilentLogin";
    public static final String ACTION_LOGOUT = "logout";
    public static final String ACTION_DISCONNECT = "disconnect";
    public static final String ACTION_GET_SIGNING_CERTIFICATE_FINGERPRINT = "getSigningCertificateFingerprint";

    private final static String FIELD_ACCESS_TOKEN      = "accessToken";
    private final static String FIELD_TOKEN_EXPIRES     = "expires";
    private final static String FIELD_TOKEN_EXPIRES_IN  = "expires_in";
    private final static String VERIFY_TOKEN_URL        = "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=";

    //String options/config object names passed in to login and trySilentLogin
    public static final String ARGUMENT_WEB_CLIENT_ID = "webClientId";
    public static final String ARGUMENT_SCOPES = "scopes";
    public static final String ARGUMENT_OFFLINE_KEY = "offline";
    public static final String ARGUMENT_HOSTED_DOMAIN = "hostedDomain";

    public static final String TAG = "GooglePlugin";
    public static final int RC_GOOGLEPLUS = 1552; // Request Code to identify our plugin's activities
    public static final int KAssumeStaleTokenSec = 60;

    private GoogleSignInClient googleSignInClient;
    private CallbackContext savedCallbackContext;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        this.savedCallbackContext = callbackContext;

        if (ACTION_IS_AVAILABLE.equals(action)) {
            final boolean avail = true;
            savedCallbackContext.success("" + avail);

        } else if (ACTION_LOGIN.equals(action)) {
            // pass args into sign-in client build
            buildGoogleSignInClient(args.optJSONObject(0));

            // Tries to Log the user in
            Log.i(TAG, "Trying to Log in!");
            cordova.setActivityResultCallback(this); //sets this class instance to be an activity result listener
            signIn();

        } else if (ACTION_TRY_SILENT_LOGIN.equals(action)) {
            // pass args into sign-in client build
            buildGoogleSignInClient(args.optJSONObject(0));

            Log.i(TAG, "Trying to do silent login!");
            trySilentLogin();

        } else if (ACTION_LOGOUT.equals(action)) {
            Log.i(TAG, "Trying to logout!");
            signOut();

        } else if (ACTION_DISCONNECT.equals(action)) {
            Log.i(TAG, "Trying to disconnect the user");
            disconnect();

        } else if (ACTION_GET_SIGNING_CERTIFICATE_FINGERPRINT.equals(action)) {
            getSigningCertificateFingerprint();

        } else {
            Log.i(TAG, "This action doesn't exist");
            return false;

        }
        return true;
    }

    /**
     * Set options for login and build the GoogleSignInClient.
     * @param clientOptions - the options object passed in the login function
     */
    private synchronized void buildGoogleSignInClient(JSONObject clientOptions) throws JSONException {
        if (clientOptions == null) {
            return;
        }

        Log.i(TAG, "Building Google options");

        // Make our SignIn Options builder.
        GoogleSignInOptions.Builder gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN);

        // request the default scopes
        gso.requestEmail().requestProfile();

        // Build scopes on the sign-in options so the JS-facing API remains unchanged.
        String scopes = clientOptions.optString(ARGUMENT_SCOPES, null);

        if (scopes != null && !scopes.isEmpty()) {
            // We have a string of scopes passed in. Split by space and request
            for (String scope : scopes.split(" ")) {
                gso.requestScopes(new Scope(scope));
            }
        }

        // Try to get web client id
        String webClientId = clientOptions.optString(ARGUMENT_WEB_CLIENT_ID, null);

        // if webClientId included, we'll request an idToken
        if (webClientId != null && !webClientId.isEmpty()) {
            gso.requestIdToken(webClientId);

            // if webClientId is included AND offline is true, we'll request the serverAuthCode
            if (clientOptions.optBoolean(ARGUMENT_OFFLINE_KEY, false)) {
                gso.requestServerAuthCode(webClientId, true);
            }
        }

        // Try to get hosted domain
        String hostedDomain = clientOptions.optString(ARGUMENT_HOSTED_DOMAIN, null);

        // if hostedDomain included, we'll request a hosted domain account
        if (hostedDomain != null && !hostedDomain.isEmpty()) {
            gso.setHostedDomain(hostedDomain);
        }

        Log.i(TAG, "Building GoogleSignInClient");
        this.googleSignInClient = GoogleSignIn.getClient(cordova.getActivity(), gso.build());
        Log.i(TAG, "GoogleSignInClient built");
    }

    // The Following functions were implemented in reference to Google's example here:
    // https://github.com/googlesamples/google-services/blob/master/android/signin/app/src/main/java/com/google/samples/quickstart/signin/SignInActivity.java

    /**
     * Starts the sign in flow with a new Intent, which should respond to our activity listener here.
     */
    private void signIn() {
        if (this.googleSignInClient == null) {
            savedCallbackContext.error("GoogleSignInClient was never initialized");
            return;
        }

        Intent signInIntent = this.googleSignInClient.getSignInIntent();
        cordova.getActivity().startActivityForResult(signInIntent, RC_GOOGLEPLUS);
    }

    /**
     * Tries to log the user in silently using existing sign in result information
     */
    private void trySilentLogin() {
        if (this.googleSignInClient == null) {
            savedCallbackContext.error("GoogleSignInClient was never initialized");
            return;
        }

        GoogleSignInAccount signedInAccount = GoogleSignIn.getLastSignedInAccount(cordova.getActivity());
        if (signedInAccount != null) {
            handleSignInAccount(signedInAccount);
            return;
        }

        this.googleSignInClient.silentSignIn().addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<GoogleSignInAccount>() {
            @Override
            public void onComplete(Task<GoogleSignInAccount> task) {
                handleSignInTask(task);
            }
        });
    }

    /**
     * Signs the user out from the client
     */
    private void signOut() {
        if (this.googleSignInClient == null) {
            savedCallbackContext.error("Please use login or trySilentLogin before logging out");
            return;
        }

        this.googleSignInClient.signOut().addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<Void>() {
            @Override
            public void onComplete(Task<Void> task) {
                if (task.isSuccessful()) {
                    savedCallbackContext.success("Logged user out");
                } else {
                    savedCallbackContext.error(getTaskStatusCode(task));
                }
            }
        });
    }

    /**
     * Disconnects the user and revokes access
     */
    private void disconnect() {
        if (this.googleSignInClient == null) {
            savedCallbackContext.error("Please use login or trySilentLogin before disconnecting");
            return;
        }

        this.googleSignInClient.revokeAccess().addOnCompleteListener(cordova.getActivity(), new OnCompleteListener<Void>() {
            @Override
            public void onComplete(Task<Void> task) {
                if (task.isSuccessful()) {
                    savedCallbackContext.success("Disconnected user");
                } else {
                    savedCallbackContext.error(getTaskStatusCode(task));
                }
            }
        });
    }

    /**
     * Listens for and responds to an activity result. If the activity result request code matches our own,
     * we know that the sign in Intent that we started has completed.
     *
     * The result is retrieved and send to the handleSignInResult function.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     * @param resultCode The integer result code returned by the child activity through its setResult().
     * @param intent Information returned by the child activity
     */
    @Override
    public void onActivityResult(int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        Log.i(TAG, "In onActivityResult");

        if (requestCode == RC_GOOGLEPLUS) {
            Log.i(TAG, "One of our activities finished up");
            handleSignInTask(GoogleSignIn.getSignedInAccountFromIntent(intent));
        }
        else {
            Log.i(TAG, "This wasn't one of our activities");
        }
    }

    /**
     * Function for handling the sign in result
     * Handles the result of the authentication workflow.
     *
     * If the sign in was successful, we build and return an object containing the users email, id, displayname,
     * id token, and (optionally) the server authcode.
     *
     * If sign in was not successful, for some reason, we return the status code to web app to be handled.
     * Some important Status Codes:
     *      SIGN_IN_CANCELLED = 12501 -> cancelled by the user, flow exited, oauth consent denied
     *      SIGN_IN_FAILED = 12500 -> sign in attempt didn't succeed with the current account
     *      SIGN_IN_REQUIRED = 4 -> Sign in is needed to access API but the user is not signed in
     *      INTERNAL_ERROR = 8
     *      NETWORK_ERROR = 7
     *
     * @param signInTask - the Google sign in task retrieved in the onActivityResult method.
     */
    private void handleSignInTask(final Task<GoogleSignInAccount> signInTask) {
        if (this.googleSignInClient == null) {
            savedCallbackContext.error("GoogleSignInClient was never initialized");
            return;
        }

        if (signInTask == null) {
            savedCallbackContext.error("SignIn task is null");
            return;
        }

        Log.i(TAG, "Handling SignIn Result");

        try {
            handleSignInAccount(signInTask.getResult(ApiException.class));
        } catch (ApiException exception) {
            Log.i(TAG, "Wasn't signed in");
            savedCallbackContext.error(exception.getStatusCode());
        }
    }

    private void handleSignInAccount(final GoogleSignInAccount account) {
        if (account == null) {
            savedCallbackContext.error("Signed in account is null");
            return;
        }

        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                JSONObject result = new JSONObject();
                try {
                    Account googleAccount = getGoogleAccount(account);
                    JSONObject accessTokenBundle = getAuthToken(
                        cordova.getActivity(), googleAccount, true
                    );
                    result.put(FIELD_ACCESS_TOKEN, accessTokenBundle.get(FIELD_ACCESS_TOKEN));
                    result.put(FIELD_TOKEN_EXPIRES, accessTokenBundle.get(FIELD_TOKEN_EXPIRES));
                    result.put(FIELD_TOKEN_EXPIRES_IN, accessTokenBundle.get(FIELD_TOKEN_EXPIRES_IN));
                    result.put("email", account.getEmail());
                    result.put("idToken", account.getIdToken());
                    result.put("serverAuthCode", account.getServerAuthCode());
                    result.put("userId", account.getId());
                    result.put("displayName", account.getDisplayName());
                    result.put("familyName", account.getFamilyName());
                    result.put("givenName", account.getGivenName());
                    result.put("imageUrl", account.getPhotoUrl());
                    savedCallbackContext.success(result);
                } catch (Exception e) {
                    savedCallbackContext.error("Trouble obtaining result, error: " + e.getMessage());
                }
            }
        });
    }

    private Account getGoogleAccount(GoogleSignInAccount account) {
        Account googleAccount = account.getAccount();
        if (googleAccount != null) {
            return googleAccount;
        }

        String email = account.getEmail();
        if (email == null || email.isEmpty()) {
            throw new IllegalStateException("Signed in account does not contain an Android account");
        }

        return new Account(email, "com.google");
    }

    private int getTaskStatusCode(Task<?> task) {
        Exception exception = task.getException();
        if (exception instanceof ApiException) {
            return ((ApiException) exception).getStatusCode();
        }
        return -1;
    }

    private void getSigningCertificateFingerprint() {
        String packageName = webView.getContext().getPackageName();
        int flags = PackageManager.GET_SIGNATURES;
        PackageManager pm = webView.getContext().getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, flags);
            Signature[] signatures = packageInfo.signatures;
            byte[] cert = signatures[0].toByteArray();

            String strResult = "";
            MessageDigest md;
            md = MessageDigest.getInstance("SHA1");
            md.update(cert);
            for (byte b : md.digest()) {
                String strAppend = Integer.toString(b & 0xff, 16);
                if (strAppend.length() == 1) {
                    strResult += "0";
                }
                strResult += strAppend;
                strResult += ":";
            }
            // strip the last ':'
            strResult = strResult.substring(0, strResult.length()-1);
            strResult = strResult.toUpperCase();
            this.savedCallbackContext.success(strResult);

        } catch (Exception e) {
            e.printStackTrace();
            savedCallbackContext.error(e.getMessage());
        }
    }

    private JSONObject getAuthToken(Activity activity, Account account, boolean retry) throws Exception {
        AccountManager manager = AccountManager.get(activity);
        AccountManagerFuture<Bundle> future = manager.getAuthToken(account, "oauth2:profile email", null, activity, null, null);
        Bundle bundle = future.getResult();
        String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
        try {
            return verifyToken(authToken);
        } catch (IOException e) {
            if (retry) {
                manager.invalidateAuthToken("com.google", authToken);
                return getAuthToken(activity, account, false);
            } else {
                throw e;
            }
        }
    }

    private JSONObject verifyToken(String authToken) throws IOException, JSONException {
        URL url = new URL(VERIFY_TOKEN_URL+authToken);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setInstanceFollowRedirects(true);
        String stringResponse = fromStream(
            new BufferedInputStream(urlConnection.getInputStream())
        );
        /* expecting:
        {
            "issued_to": "608941808256-43vtfndets79kf5hac8ieujto8837660.apps.googleusercontent.com",
            "audience": "608941808256-43vtfndets79kf5hac8ieujto8837660.apps.googleusercontent.com",
            "user_id": "107046534809469736555",
            "scope": "https://www.googleapis.com/auth/userinfo.profile",
            "expires_in": 3595,
            "access_type": "offline"
        }*/

        Log.d("AuthenticatedBackend", "token: " + authToken + ", verification: " + stringResponse);
        JSONObject jsonResponse = new JSONObject(
            stringResponse
        );
        int expires_in = jsonResponse.getInt(FIELD_TOKEN_EXPIRES_IN);
        if (expires_in < KAssumeStaleTokenSec) {
            throw new IOException("Auth token soon expiring.");
        }
        jsonResponse.put(FIELD_ACCESS_TOKEN, authToken);
        jsonResponse.put(FIELD_TOKEN_EXPIRES, expires_in + (System.currentTimeMillis()/1000));
        return jsonResponse;
    }

    public static String fromStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}
