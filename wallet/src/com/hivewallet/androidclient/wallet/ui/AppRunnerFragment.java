package com.hivewallet.androidclient.wallet.ui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.RetryPolicy;
import com.android.volley.VolleyError;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.common.base.Joiner;
import com.hivewallet.androidclient.wallet.Configuration;
import com.hivewallet.androidclient.wallet.Constants;
import com.hivewallet.androidclient.wallet.ExchangeRatesProvider;
import com.hivewallet.androidclient.wallet.PaymentIntent;
import com.hivewallet.androidclient.wallet.WalletApplication;
import com.hivewallet.androidclient.wallet.ExchangeRatesProvider.ExchangeRate;
import com.hivewallet.androidclient.wallet.integration.android.BitcoinIntegration;
import com.hivewallet.androidclient.wallet.util.AppInstaller;
import com.hivewallet.androidclient.wallet.util.AppPlatformDBHelper;
import com.hivewallet.androidclient.wallet.util.GenericUtils;
import com.hivewallet.androidclient.wallet.util.StringPlus;
import com.hivewallet.androidclient.wallet.util.StringPlusRequest;
import com.hivewallet.androidclient.wallet.R;

@SuppressLint("SetJavaScriptEnabled")
public class AppRunnerFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor>
{
	private static final Logger log = LoggerFactory.getLogger(AppRunnerFragment.class);
	private static final int REQUEST_CODE_SEND_MONEY = 0;
	private static final String HIVE_ANDROID_APP_PLATFORM_JS = "hive_android_app_platform.min.js";
	private static final boolean ALLOW_EXTERNAL_URLS = true;
	private static final String ARGUMENT_APP_ID = "app_id";
	private static final String TX_TYPE_OUTGOING = "outgoing";
	private static final String TX_TYPE_INCOMING = "incoming";
	private static final int ID_RATE_LOADER = 0;
	
	private Activity activity;
	
	private WebView webView;
	
	private AppPlatformApi appPlatformApi;
	private String platformJS;
	
	public static AppRunnerFragment newInstance(String appId) {
		AppRunnerFragment f = new AppRunnerFragment();
		
		Bundle args = new Bundle();
		args.putString(ARGUMENT_APP_ID, appId);
		f.setArguments(args);
		
		return f;
	}
	
	public AppRunnerFragment() { /* required default constructor */ }
	
	@Override
	public void onAttach(Activity activity)
	{
		super.onAttach(activity);
		
		this.activity = activity;
		
		try
		{
			final InputStream is = activity.getAssets().open(HIVE_ANDROID_APP_PLATFORM_JS);
			platformJS = IOUtils.toString(is, Charset.defaultCharset());
		}
		catch (IOException e)
		{
			throw new RuntimeException("Error while loading platform javascript layer", e);
		}
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
		Bundle args = getArguments();
		if (args == null)
			throw new IllegalArgumentException("This fragment requires arguments.");
		
		String appId = args.getString(ARGUMENT_APP_ID);
		if (appId == null)
			throw new IllegalArgumentException("App id needs to be provided");
		
		String appBaseURL = "http://" + appId + ".hiveapp/";
		
		View view = inflater.inflate(R.layout.app_runner_fragment, container, false);
		webView = (WebView)view.findViewById(R.id.wv_app_runner);

		webView.setWebViewClient(new AppPlatformWebViewClient(activity, appId, appBaseURL));
		webView.addJavascriptInterface(new AppPlatformApiLoader(platformJS), "hive");
		appPlatformApi = new AppPlatformApi(this, webView, appId);
		webView.addJavascriptInterface(appPlatformApi, "__bitcoin");
		
		webView.getSettings().setJavaScriptEnabled(true);
		webView.loadUrl("javascript:" + platformJS);
		
		try {
			InputStream is = null;
			if (Constants.APP_STORE_ID.equals(appId))
				is = activity.getAssets().open(Constants.APP_STORE_ID + "/index.html");
			else
				is = getAppInputStream(activity, appId, "index.html");

			if (is == null)
				throw new IOException("InputStream is null after trying to load app index.html file.");
				
			String data = IOUtils.toString(is); 
			webView.loadDataWithBaseURL(appBaseURL, data, null, "UTF-8", null);
		} catch (IOException e) {
			throw new RuntimeException("Unable to load index.html for " + appId, e);
		}
		
		return view;
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		if (requestCode == REQUEST_CODE_SEND_MONEY) {
			if (resultCode == Activity.RESULT_OK) {
				final String txHash = BitcoinIntegration.transactionHashFromResult(data);
				appPlatformApi.sendMoneyResult(true, txHash);
			} else {
				appPlatformApi.sendMoneyResult(false, null);
			}
		}
	}
	
	@Override
	public void onResume()
	{
		super.onResume();
		
		getLoaderManager().initLoader(ID_RATE_LOADER, null, this);
	}
	
	@Override
	public void onPause()
	{
		getLoaderManager().destroyLoader(ID_RATE_LOADER);
		
		appPlatformApi.onPause();
		
		super.onPause();
	}
	
	@Override
	public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
	{
		return new CursorLoader(activity, ExchangeRatesProvider.contentUri(activity.getPackageName()), null, null, null, null);
	}

	@Override
	public void onLoadFinished(final Loader<Cursor> loader, final Cursor cursor)
	{
		List<ExchangeRate> exchangeRates = new ArrayList<ExchangeRatesProvider.ExchangeRate>();
		for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
			exchangeRates.add(ExchangeRatesProvider.getExchangeRate(cursor));
		}
		appPlatformApi.setExchangeRates(exchangeRates);
	}

	@Override
	public void onLoaderReset(final Loader<Cursor> loader)
	{
	}	
	
	private static class AppPlatformWebViewClient extends WebViewClient {
		private Activity activity;
		private String appId;
		private String appBaseURL;
		
		public AppPlatformWebViewClient(Activity activity, String appId, String appBaseURL)
		{
			super();
			
			this.activity = activity;
			this.appId = appId;
			this.appBaseURL = appBaseURL;
		}
		
		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, String url)
		{
			Uri uri = Uri.parse(url);
			if (uri == null)
				return null;
			
			String baseURL = "http://" + uri.getHost() + "/";
			if (baseURL.equalsIgnoreCase(appBaseURL)) {
				// simulate virtual host
				String path = uri.getPath();
				if (path == null || path.isEmpty() || path.equals("/"))
					path = "/index.html";
				
				InputStream is = null;
				if (Constants.APP_STORE_ID.equals(appId)) {
					try
					{
						is = activity.getAssets().open(Constants.APP_STORE_ID + path);
					}
					catch (IOException ignored) { }
				} else {
					is = getAppInputStream(activity, appId, path);
				}
				
				if (is == null) {
					log.info("404 for app {} when accessing {}", appId, url);
					is = new ByteArrayInputStream("404 - file not found".getBytes(Charset.defaultCharset()));
				}
				
				return new WebResourceResponse(null, "UTF-8", is);	// we don't know the MIME type;
																	// WebView will hopefully figure it out
			} else {
				return null;
			}
		}
		
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url)
		{
			String lcUrl = url.toLowerCase(Locale.US);
			boolean isHttp = lcUrl.startsWith("http://") || lcUrl.startsWith("https://");
			
			// disallow non-http links
			if (!isHttp) {
				log.info("Blocked access to URL: {}", url);
				return true;	// we grab control for it
			}
			
			// it's an http link; do further checks on it
			if (ALLOW_EXTERNAL_URLS) {
				return false;	// let the WebView handle it normally
			} else {
				boolean accessAllowed = lcUrl.startsWith(appBaseURL);
				
				if (accessAllowed) {
					return false;
				} else {
					Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
					activity.startActivity(intent);
					return true;
				}
			}
		}
	}
	
	private static class AppPlatformApiLoader {
		private String platformJS;
		
		public AppPlatformApiLoader(String platformJS)
		{
			this.platformJS = platformJS;
		}
		
		@JavascriptInterface
		public String init() {
			return platformJS;
		}
	}
	
	private static class AppPlatformApi implements AppInstaller.AppInstallCallback {
		private static final Logger log = LoggerFactory.getLogger(AppPlatformApi.class);
		private static final String VOLLEY_TAG = "volley_tag";
		
		private WalletApplication application;
		private Configuration config;
		private Fragment fragment;
		private Activity activity;
		private WebView webView;
		
		volatile private long lastSendMoneyCallbackId = -1;
		volatile private long lastInstallAppCallbackId = -1;
		
		volatile private List<ExchangeRate> exchangeRates;
		private boolean shouldForwardExchangeRateUpdates = false; 
		
		private AppPlatformDBHelper appPlatformDBHelper;
		private AppInstaller appInstaller;
		
		private List<String> accessedHosts;
		
		public AppPlatformApi(Fragment fragment, WebView webView, String appId)
		{
			this.application = (WalletApplication)fragment.getActivity().getApplication();
			this.config = application.getConfiguration();
			this.fragment = fragment;
			this.activity = fragment.getActivity();
			this.webView = webView;
			this.appPlatformDBHelper = application.getAppPlatformDBHelper();
			this.accessedHosts = appPlatformDBHelper.getAccessedHosts(appId);
		}
		
		@JavascriptInterface
		public void getUserInfo(long callbackId) {
			Address address = application.determineSelectedAddress();
			Map<String, String> info = new HashMap<String, String>();
			
			info.put("firstName", "'Hive user'");
			info.put("lastName", "''");
			info.put("address", "'" + address.toString() + "'");
			performCallback(callbackId, toJSDataStructure(info));
		}
		
		@JavascriptInterface
		public void getSystemInfo(long callbackId) {
			Map<String, String> info = new HashMap<String, String>();
			
			String version = application.packageInfo().versionName;
			
			info.put("version", "'" + version + "'");
			info.put("buildNumber", "'" + version + "'");
			info.put("platform", "'android'");
			
			info.put("decimalSeparator", "'.'");	// always use Locale.US at the moment
			info.put("locale", "'" + Locale.getDefault().toString() + "'");
			
			info.put("preferredBitcoinFormat", "'" + config.getBtcPrefix() + "'");
			
			String exchangeCurrencyCode = config.getExchangeCurrencyCode();
			if (exchangeCurrencyCode != null) {
				info.put("preferredCurrency", "'" + exchangeCurrencyCode.toString()  + "'");
			} else {
				String defaultCurrencyCode = ExchangeRatesProvider.defaultCurrencyCode();
				if (defaultCurrencyCode == null) defaultCurrencyCode = "USD";
				
				info.put("preferredCurrency", "'" + defaultCurrencyCode + "'");
			}
			
			List<String> currencies = new ArrayList<String>();
			for (String currency : config.getCachedExchangeCurrencies()) {
				currencies.add("'" + currency + "'");
			}
			info.put("availableCurrencies", "[" + Joiner.on(',').join(currencies) + "]");
			
			info.put("onTestnet", Constants.TEST ? "true" : "false");
			performCallback(callbackId, toJSDataStructure(info));
		}
		
		@JavascriptInterface
		public String userStringForSatoshi(long longAmount) {
			BigInteger amount = BigInteger.valueOf(longAmount);
			return GenericUtils.formatValue(amount, config.getBtcPrecision(), config.getBtcShift());
		}
		
		@JavascriptInterface
		public long satoshiFromUserString(String amountStr) {
			int shift = config.getBtcShift();
			BigInteger amount = GenericUtils.parseValue(amountStr, shift);
			return amount.longValue();
		}

		@JavascriptInterface
		public String userStringForCurrencyValue(long longAmount) {
			BigInteger amount = BigInteger.valueOf(longAmount);
			return GenericUtils.formatValue(amount, Constants.LOCAL_PRECISION, 0);
		}

		@JavascriptInterface
		public long valueFromUserString(String amountStr) {
			BigInteger amount = GenericUtils.parseValue(amountStr, 0);
			return amount.longValue();
		}
		
		@JavascriptInterface
		public void sendMoney1(long callbackId, String addressStr, long amountLong) {
			Address address = null;
			
			try {
				address = new Address(Constants.NETWORK_PARAMETERS, addressStr);
			} catch (AddressFormatException e) { /* ignore address */ }
			
			BigInteger amount = null;
			if (amountLong >= 0)
				amount = BigInteger.valueOf(amountLong);

			PaymentIntent paymentIntent = null;
			if (address != null)
				paymentIntent = PaymentIntent.fromAddressAndAmount(address, amount);
			
			final Intent intent = new Intent(activity, SendCoinsActivity.class);
			if (paymentIntent != null)
				intent.putExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT, paymentIntent);
			
			lastSendMoneyCallbackId = callbackId;
			fragment.startActivityForResult(intent, REQUEST_CODE_SEND_MONEY);
		}
		
		@JavascriptInterface
		public void sendMoney2(long callbackId, String addressStr) {
			sendMoney1(callbackId, addressStr, -1);
		}
		
		public void sendMoneyResult(boolean success, @Nullable String txHash) {
			if (lastSendMoneyCallbackId == -1)
				return;
			
			if (txHash != null) {
				performCallback(lastSendMoneyCallbackId, success ? "true" : "false", "'" + txHash + "'");
			} else {
				performCallback(lastSendMoneyCallbackId, success ? "true" : "false", "null");
			}
			
			lastSendMoneyCallbackId = -1;
		}
		
		@SuppressWarnings("deprecation")
		@JavascriptInterface
		public void getTransaction(long callbackId, String txid) {
			Wallet wallet = application.getWallet();
			Transaction tx = null;
			
			try {
				Sha256Hash hash = new Sha256Hash(txid);
				tx = wallet.getTransaction(hash);
			} catch (IllegalArgumentException e) { /* handle below */ };
			
			if (tx != null) {
				Map<String, String> info = new HashMap<String, String>();
				final BigInteger value = tx.getValue(wallet);
				final boolean outgoing = value.signum() < 0;
				
				info.put("id", "'" + tx.getHashAsString() + "'");
				info.put("amount", value.abs().toString());
				info.put("type", outgoing ? "'" + TX_TYPE_OUTGOING + "'" : "'" + TX_TYPE_INCOMING + "'");
				info.put("timestamp", "'" + asISOString(tx.getUpdateTime()) + "'");
				
				List<String> inputAddresses = new ArrayList<String>();
				for (TransactionInput input : tx.getInputs()) {
					try {
						Address address = input.getScriptSig().getFromAddress(Constants.NETWORK_PARAMETERS);
						inputAddresses.add("'" + address.toString() + "'");
					} catch (ScriptException e) { /* skip input */ } 
				}
				
				List<String> outputAddresses = new ArrayList<String>();
				for (TransactionOutput output : tx.getOutputs()) {
					try {
						Address address = output.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
						outputAddresses.add("'" + address.toString() + "'");
					} catch (ScriptException e) { /* skip output */ } 
				}
				
				info.put("inputAddresses", "[" + Joiner.on(',').join(inputAddresses) + "]");
				info.put("outputAddresses", "[" + Joiner.on(',').join(outputAddresses) + "]");
				
				performCallback(callbackId, toJSDataStructure(info));
			} else {
				performCallback(callbackId, "null");
			}
		}
		
		@JavascriptInterface
		public void subscribeToExchangeRateUpdates() {
			shouldForwardExchangeRateUpdates = true;
		}
		
		@JavascriptInterface
		public void unsubscribeFromExchangeRateUpdates() {
			shouldForwardExchangeRateUpdates = false;
		}
		
		@JavascriptInterface
		public void updateExchangeRate(String currency) {
			maybeForwardExchangeRateUpdates(currency);	// if do not have data yet, we will forward it
														// as soon as we receive it
		}
		
		@JavascriptInterface
		public void makeRequest(long callbackId, String url, String method, String data) {
			final long myCallbackId = callbackId;
			
			boolean appendDataToURL = method.equalsIgnoreCase("get")
					|| method.equalsIgnoreCase("head")
					|| method.equalsIgnoreCase("delete");
			if (appendDataToURL && !data.isEmpty())
				url += "?" + data;
			
			Uri uri = Uri.parse(url);
			if (uri == null) {
				performCallback(myCallbackId, "false", "''", "500", "'Invalid URL'");
				return;
			}
			
			String host = uri.getHost().toLowerCase(Locale.US);
			if (!accessedHosts.contains(host)) {
				performCallback(myCallbackId, "false", "''", "403", "'Host not listed in manifest file'");
				return;
			}
			
			int methodCode;
			if (method.equalsIgnoreCase("get"))
				methodCode = Request.Method.GET;
			else if (method.equalsIgnoreCase("head"))
				methodCode = Request.Method.HEAD;
			else if (method.equalsIgnoreCase("delete"))
				methodCode = Request.Method.DELETE;
			else if (method.equalsIgnoreCase("post"))
				methodCode = Request.Method.POST;
			else if (method.equalsIgnoreCase("put"))
				methodCode = Request.Method.PUT;
			else {
				performCallback(myCallbackId, "false", "''", "500", "'Invalid HTTP method'");
				return;
			}
			
			byte[] postData = null;
			if (!appendDataToURL)
				postData = data.getBytes();
			
			RetryPolicy retryPolicy = new DefaultRetryPolicy(15 * 1000,
					DefaultRetryPolicy.DEFAULT_MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT);
			
			RequestQueue rq = application.getVolleyRequestQueue();
			StringPlusRequest sr = new StringPlusRequest(methodCode, uri.toString(), new Response.Listener<StringPlus>()
			{
				@Override
				public void onResponse(StringPlus responsePlus)
				{
					String response = responsePlus.getString();
					String encodedResponse = Base64.encodeToString(response.getBytes(), Base64.NO_WRAP);
					
					performCallback(myCallbackId, "true",
							"'" + responsePlus.getContentType() + "'", "'" + encodedResponse + "'", "200");
				}
			}, new Response.ErrorListener()
			{
				@Override
				public void onErrorResponse(VolleyError error)
				{
					int status = 500;
					String encodedResponse = "";
					
					NetworkResponse networkResponse = error.networkResponse;
					if (networkResponse != null) {
						status = networkResponse.statusCode;
						encodedResponse = Base64.encodeToString(networkResponse.data, Base64.NO_WRAP);
					}
					
					performCallback(myCallbackId, "false",
							"'" + encodedResponse + "'", Integer.toString(status), "'" + error.getMessage() + "'");
				}
			}, postData);
			sr.setRetryPolicy(retryPolicy);
			sr.setTag(VOLLEY_TAG);
			rq.add(sr);
		}
		
		synchronized private void maybeForwardExchangeRateUpdates() {
			maybeForwardExchangeRateUpdates(null);
		}
		
		synchronized private void maybeForwardExchangeRateUpdates(@Nullable String currency) {
			if (!shouldForwardExchangeRateUpdates)
				return;
			
			if (exchangeRates == null)
				return;
			
			Map<String, String> info = new HashMap<String, String>();
			for (ExchangeRate exchangeRate : exchangeRates) {
				if (currency != null && !exchangeRate.currencyCode.equalsIgnoreCase(currency))
					continue;
				
				info.put(exchangeRate.currencyCode,
						GenericUtils.formatValue(exchangeRate.rate, Constants.BTC_MAX_PRECISION, 0));
			}
			forwardExchangeRateUpdate(toJSDataStructure(info));
		}
		
		@JavascriptInterface
		public void getApplication(long callbackId, String appId) {
			Map<String, String> manifest = appPlatformDBHelper.getAppManifest(appId);
			
			if (manifest == null) {
				performCallback(callbackId, "null");
			} else {
				performCallback(callbackId, toJSDataStructure(manifest));
			}
		}
		
		@JavascriptInterface
		public void installApp(long callbackId, String url) {
			if (appInstaller != null) {
				/* Concurrent install are not supported at the moment */
				performCallback(callbackId, "'Another install is in progress'", "false");
				return;
			}
			
			lastInstallAppCallbackId = callbackId;
			File dir = application.getDir(Constants.APP_PLATFORM_FOLDER, Context.MODE_PRIVATE);
			appInstaller = new AppInstaller(url, dir, this);
			appInstaller.start();
		}
		
		@Override
		public void installSuccessful(String appId, File unpackDir, File appDir, JSONObject manifest)
		{
			try
			{
				FileUtils.deleteQuietly(appDir);
				FileUtils.moveDirectory(unpackDir, appDir);
				appPlatformDBHelper.addManifest(appId, manifest);
				
				appInstaller = null;
				if (lastInstallAppCallbackId != -1)
					performCallback(lastInstallAppCallbackId, "null", "true");
				lastInstallAppCallbackId = -1;
			}
			catch (IOException e)
			{
				log.info("Exception while finalizing installation: {}", e);
				installFailed("Install failed");
			}
		}

		@Override
		public void installFailed(String errMsg)
		{
			appInstaller = null;
			if (lastInstallAppCallbackId != -1)
				performCallback(lastInstallAppCallbackId, "'" + errMsg + "'", "false");
			lastInstallAppCallbackId = -1;
		}		
		
		private void performCallback(long callbackId, String... arguments) {
			if (arguments == null || arguments.length < 1)
				throw new IllegalArgumentException("Need at least one argument");
			
			final String furtherArguments = Joiner.on(',').join(arguments);
			final String js = "javascript:bitcoin.__callbackFromAndroid(" + callbackId + "," + furtherArguments + ");";
			activity.runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					webView.loadUrl(js);
				}
			});			
		}
		
		private void forwardExchangeRateUpdate(String update) {
			final String js = "javascript:bitcoin.__exchangeRateUpdateFromAndroid(" + update + ");";
			activity.runOnUiThread(new Runnable()
			{
				@Override
				public void run()
				{
					webView.loadUrl(js);
				}
			});			
		}
		
		public void setExchangeRates(List<ExchangeRate> exchangeRates) {
			this.exchangeRates = exchangeRates;
			maybeForwardExchangeRateUpdates();
		}
		
		public void onPause() {
			if (appInstaller != null)
				appInstaller.cancel();
			application.getVolleyRequestQueue().cancelAll(VOLLEY_TAG);
		}
		
		private static String toJSDataStructure(Map<String, String> entries) {
			Map<String, String> ppEntries = new HashMap<String, String>();
			for (Entry<String, String> entry : entries.entrySet()) {
				ppEntries.put("'" + entry.getKey() + "'", entry.getValue());
			}
			
			String sEntries = Joiner.on(',').withKeyValueSeparator(":").join(ppEntries);
			return "{" + sEntries + "}";
		}
		
		private static String asISOString(Date date) {
			TimeZone tz = TimeZone.getTimeZone("UTC");
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
			df.setTimeZone(tz);
			return df.format(date);
		}
	}
	
	private static InputStream getAppInputStream(Context context, String appId, String path) {
		File appPlatform = context.getDir(Constants.APP_PLATFORM_FOLDER, Context.MODE_PRIVATE);
		File appsDir = new File(appPlatform, Constants.APP_PLATFORM_APP_FOLDER);
		File appDir = new File(appsDir, appId);
		File file = new File(appDir, path);

		// double check permission
		String appDirAbsolutePath = appDir.getAbsolutePath();
		String fileAbsolutePath = file.getAbsolutePath();
		if (!fileAbsolutePath.startsWith(appDirAbsolutePath))
			return null;
		
		try
		{
			return FileUtils.openInputStream(file);
		}
		catch (IOException e)
		{
			return null;
		} 
	}		
}
