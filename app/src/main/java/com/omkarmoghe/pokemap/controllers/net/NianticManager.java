package com.omkarmoghe.pokemap.controllers.net;

import android.os.HandlerThread;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.omkarmoghe.pokemap.models.events.CatchablePokemonEvent;

import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import com.omkarmoghe.pokemap.models.events.GymsEvent;
import com.omkarmoghe.pokemap.models.events.InternalExceptionEvent;
import com.omkarmoghe.pokemap.models.events.LoginEventResult;
import com.omkarmoghe.pokemap.models.events.PokestopsEvent;
import com.omkarmoghe.pokemap.models.events.ServerUnreachableEvent;
import com.omkarmoghe.pokemap.models.map.SearchParams;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.pokemon.CatchablePokemon;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.PtcLogin;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import POGOProtos.Map.Fort.FortDataOuterClass;
import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static com.pokegoapi.auth.PtcLogin.CLIENT_ID;
import static com.pokegoapi.auth.PtcLogin.CLIENT_SECRET;
import static com.pokegoapi.auth.PtcLogin.LOGIN_OAUTH;
import static com.pokegoapi.auth.PtcLogin.LOGIN_URL;
import static com.pokegoapi.auth.PtcLogin.REDIRECT_URI;

/**
 * Created by vanshilshah on 20/07/16.
 */
public class NianticManager {
    private static final String TAG = "NianticManager";

    private static final String BASE_URL = "https://sso.pokemon.com/sso/";

    private static final NianticManager instance = new NianticManager();

    private Handler mHandler;
    private AuthInfo mAuthInfo;
    private NianticService mNianticService;
    private final OkHttpClient mClient;
    private final OkHttpClient mPoGoClient;
    private PokemonGo mPokemonGo;

    public static NianticManager getInstance(){
        return instance;
    }

    private NianticManager(){
        mPoGoClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        HandlerThread thread = new HandlerThread("Niantic Manager Thread");
        thread.start();
        mHandler = new Handler(thread.getLooper());

                  /*
		This is a temporary, in-memory cookie jar.
		We don't require any persistence outside of the scope of the login,
		so it being discarded is completely fine
		*/
        CookieJar tempJar = new CookieJar() {
            private final HashMap<String, List<Cookie>> cookieStore = new HashMap<String, List<Cookie>>();

            @Override
            public void saveFromResponse(okhttp3.HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), cookies);
            }

            @Override
            public List<Cookie> loadForRequest(okhttp3.HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<Cookie>();
            }
        };

        Gson gson = new GsonBuilder()
                .setLenient()
                .create();

        mClient = new OkHttpClient.Builder()
                .cookieJar(tempJar)
                .addInterceptor(new NetworkRequestLoggingInterceptor())
                .build();

        mNianticService = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(mClient)
                .build()
                .create(NianticService.class);
    }

    public void login(final String username, final String password, final LoginListener loginListener){
        Callback<NianticService.LoginValues> valuesCallback = new Callback<NianticService.LoginValues>() {
            @Override
            public void onResponse(Call<NianticService.LoginValues> call, Response<NianticService.LoginValues> response) {
                if(response.body() != null) {
                    loginPTC(username, password, response.body(), loginListener);
                } else {
                    Log.e(TAG, "PTC login failed via login(). There was no response.body().");
                    loginListener.authFailed("Fetching Pokemon Trainer Club's Login Url Values Failed");
                }

            }

            @Override
            public void onFailure(Call<NianticService.LoginValues> call, Throwable t) {
                t.printStackTrace();
                Log.e(TAG, "PTC login failed via login(). valuesCallback.onFailure() threw: " + t.getMessage());
                loginListener.authFailed("Fetching Pokemon Trainer Club's Login Url Values Failed");
            }
        };
        Call<NianticService.LoginValues> call = mNianticService.getLoginValues();
        call.enqueue(valuesCallback);
    }

    private void loginPTC(final String username, final String password, NianticService.LoginValues values, final LoginListener loginListener){
        HttpUrl url = HttpUrl.parse(LOGIN_URL).newBuilder()
                .addQueryParameter("lt", values.getLt())
                .addQueryParameter("execution", values.getExecution())
                .addQueryParameter("_eventId", "submit")
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .build();

        OkHttpClient client = mClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();

        NianticService service = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build()
                .create(NianticService.class);

        Callback<NianticService.LoginResponse> loginCallback = new Callback<NianticService.LoginResponse>() {
            @Override
            public void onResponse(Call<NianticService.LoginResponse> call, Response<NianticService.LoginResponse> response) {
                String location = response.headers().get("location");
                if (location != null) {
                    String ticket = location.split("ticket=")[1];
                    requestToken(ticket, loginListener);
                } else {
                    Log.e(TAG, "PTC login failed via loginPTC(). There was no location header in response.");
                    loginListener.authFailed("Pokemon Trainer Club Login Failed");
                }
            }

            @Override
            public void onFailure(Call<NianticService.LoginResponse> call, Throwable t) {
                t.printStackTrace();
                Log.e(TAG, "PTC login failed via loginPTC(). loginCallback.onFailure() threw: " + t.getMessage());
                loginListener.authFailed("Pokemon Trainer Club Login Failed");
            }
        };
        Call<NianticService.LoginResponse> call = service.login(url.toString());
        call.enqueue(loginCallback);
    }

    private void requestToken(String code, final LoginListener loginListener){
        Log.d(TAG, "requestToken() called with: code = [" + code + "]");
        HttpUrl url = HttpUrl.parse(LOGIN_OAUTH).newBuilder()
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("client_secret", CLIENT_SECRET)
                .addQueryParameter("grant_type", "refresh_token")
                .addQueryParameter("code", code)
                .build();

        Callback<ResponseBody> authCallback = new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                try {
                    String token = response.body().string().split("token=")[1];

                    if (token != null) {
                        token = token.split("&")[0];

                        loginListener.authSuccessful(token);
                    } else {
                        Log.e(TAG, "PTC login failed while fetching a requestToken via requestToken(). Token is null.");
                        loginListener.authFailed("Pokemon Trainer Club Login Failed");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, "PTC login failed while fetching a requestToken authCallback.onResponse() raised: " + e.getMessage());
                    loginListener.authFailed("Pokemon Trainer Club Authentication Failed");
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                t.printStackTrace();
                Log.e(TAG, "PTC login failed while fetching a requestToken authCallback.onResponse() threw: " + t.getMessage());
                loginListener.authFailed("Pokemon Trainer Club Authentication Failed");
            }
        };
        Call<ResponseBody> call = mNianticService.requestToken(url.toString());
        call.enqueue(authCallback);
    }

    public interface LoginListener {
        void authSuccessful(String authToken);
        void authFailed(String message);
    }

    /**
     * Sets the google auth token for the auth info also invokes the onLogin callback.
     * @param token - a valid google auth token.
     */
    public void setGoogleAuthToken(@NonNull final String token) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
            try {
                mAuthInfo = new GoogleLogin(mPoGoClient).login(token);
                mPokemonGo = new PokemonGo(mAuthInfo, mPoGoClient);
                EventBus.getDefault().post(new LoginEventResult(true, mAuthInfo, mPokemonGo));
            } catch (RemoteServerException | LoginFailedException | RuntimeException e) {
                e.printStackTrace();
                Log.e(TAG, "Setting google auth token failed. setGoogleAuthToken() raised: " + e.getMessage());
                EventBus.getDefault().post(new LoginEventResult(false, null, null));
            }
            }
        });
    }

    /**
     * Sets the pokemon trainer club auth token for the auth info also invokes the onLogin callback.
     * @param token - a valid pokemon trainer club auth token.
     */
    public void setPTCAuthToken(@NonNull final String token) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mAuthInfo = new PtcLogin(mPoGoClient).login(token);
                    mPokemonGo = new PokemonGo(mAuthInfo, mPoGoClient);
                    EventBus.getDefault().post(new LoginEventResult(true, mAuthInfo, mPokemonGo));
                } catch (RemoteServerException | LoginFailedException | RuntimeException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to set the PTC auth token on PoGoAPI via setPTCAuthToken(). Raised: " + e.getMessage());
                    EventBus.getDefault().post(new LoginEventResult(false, null, null));
                }
            }
        });
    }

    public void login(@NonNull final String username, @NonNull final String password) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mAuthInfo = new PtcLogin(mPoGoClient).login(username, password);
                    mPokemonGo = new PokemonGo(mAuthInfo, mPoGoClient);
                    EventBus.getDefault().post(new LoginEventResult(true, mAuthInfo, mPokemonGo));
                } catch (RemoteServerException | LoginFailedException | RuntimeException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to PTC login using PoGoAPI via login(). Raised: " + e.getMessage());
                    EventBus.getDefault().post(new LoginEventResult(false, null, null));
                }
            }
        });
    }

    public void getCatchablePokemon(final double lat, final double longitude, final double alt){
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {

                    if (mPokemonGo != null) {

                        Thread.sleep(33);
                        mPokemonGo.setLocation(lat, longitude, alt);
                        Thread.sleep(33);
                        EventBus.getDefault().post(new CatchablePokemonEvent(mPokemonGo.getMap().getCatchablePokemon(), lat, longitude));
                    }

                } catch (LoginFailedException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getCatchablePokemon(). Login credentials wrong or user banned. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new LoginEventResult(false, null, null));
                } catch (RemoteServerException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getCatchablePokemon(). Remote server unreachable. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new ServerUnreachableEvent(e));
                } catch (InterruptedException | RuntimeException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getCatchablePokemon(). PoGoAPI crashed. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new InternalExceptionEvent(e));
                }
            }
        });
    }

    public void getPokeStops(final double lat, final double longitude, final double alt){
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {

                    if (mPokemonGo != null) {

                        Thread.sleep(33);
                        mPokemonGo.setLocation(lat, longitude, alt);
                        Thread.sleep(33);
                        EventBus.getDefault().post(new PokestopsEvent(mPokemonGo.getMap().getMapObjects().getPokestops(), lat, longitude));
                    }

                } catch (LoginFailedException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getPokeStops(). Login credentials wrong or user banned. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new LoginEventResult(false, null, null));
                } catch (RemoteServerException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getPokeStops(). Remote server unreachable. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new ServerUnreachableEvent(e));
                } catch (InterruptedException | RuntimeException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to fetch map information via getPokeStops(). PoGoAPI crashed. Raised: " + e.getMessage());
                    EventBus.getDefault().post(new InternalExceptionEvent(e));
                }
            }
        });
    }

    public void getGyms(final double latitude, final double longitude, final double alt) {

        mHandler.post(new Runnable() {
            @Override
            public void run() {
            try {

                if (mPokemonGo != null) {

                    Thread.sleep(33);
                    mPokemonGo.setLocation(latitude, longitude, alt);
                    Thread.sleep(33);
                    EventBus.getDefault().post(new GymsEvent(mPokemonGo.getMap().getMapObjects().getGyms(), latitude, longitude));
                }

            } catch (LoginFailedException e) {
                e.printStackTrace();
                Log.e(TAG, "Failed to fetch map information via getGyms(). Login credentials wrong or user banned. Raised: " + e.getMessage());
                EventBus.getDefault().post(new LoginEventResult(false, null, null));
            } catch (RemoteServerException e) {
                e.printStackTrace();
                Log.e(TAG, "Failed to fetch map information via getGyms(). Remote server unreachable. Raised: " + e.getMessage());
                EventBus.getDefault().post(new ServerUnreachableEvent(e));
            } catch (InterruptedException | RuntimeException e) {
                e.printStackTrace();
                Log.e(TAG, "Failed to fetch map information via getGyms(). PoGoAPI crashed. Raised: " + e.getMessage());
                EventBus.getDefault().post(new InternalExceptionEvent(e));
            }
            }
        });
    }
}
