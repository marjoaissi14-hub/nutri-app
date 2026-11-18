package com.marjoaissi.nutri;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Nutri en plein écran : l'app (hébergée dans Claude) s'affiche dans un navigateur intégré,
 * sans barre d'adresse ni en-tête Claude. La connexion Claude se fait une fois, dans l'app.
 * En cas de souci, un écran explique quoi faire et propose d'ouvrir Nutri dans Chrome.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Nutri";
    private static final int REQ_FICHIER = 1;
    private static final int REQ_DROITS = 2;
    private static final int REQ_CAMERA = 3;
    private static final int FOND = Color.parseColor("#14080F");
    private static final int ROSE = Color.parseColor("#F0528C");

    /** Met l'app (le cadre de l'artefact) en plein écran par-dessus l'en-tête de Claude. */
    private static final String PLEIN_ECRAN =
            "(function(){if(window.__nutri)return;window.__nutri=1;" +
            "function fix(){var l=[].slice.call(document.querySelectorAll('iframe'));if(!l.length)return;" +
            "l.sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight});var f=l[0];" +
            "if(!f||f.offsetHeight<200||f.getAttribute('data-nutri'))return;f.setAttribute('data-nutri','1');" +
            "f.style.setProperty('position','fixed','important');f.style.setProperty('inset','0','important');" +
            "f.style.setProperty('width','100vw','important');f.style.setProperty('height','100vh','important');" +
            "f.style.setProperty('max-width','none','important');f.style.setProperty('max-height','none','important');" +
            "f.style.setProperty('border','0','important');f.style.setProperty('border-radius','0','important');" +
            "f.style.setProperty('z-index','35','important');f.style.setProperty('background','#14080F','important');" +
            "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';}" +
            "fix();new MutationObserver(function(){var f=document.querySelector('iframe[data-nutri]');if(!f||!f.isConnected)fix()})" +
            ".observe(document.documentElement,{childList:true,subtree:true});setInterval(fix,2000);})();";

    private WebView web;
    private LinearLayout ecran;
    private TextView message;
    private ProgressBar roue;
    private Button boutonChrome;
    private Button boutonReessayer;
    private ValueCallback<Uri[]> rappelFichier;
    private Uri photo;
    private PermissionRequest demandeEnAttente;
    private WebChromeClient.FileChooserParams choixEnAttente;
    private final Handler minuterie = new Handler(Looper.getMainLooper());
    private boolean charge = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        noterPlantages();
        Window w = getWindow();
        w.setStatusBarColor(FOND);
        w.setNavigationBarColor(FOND);

        FrameLayout racine = new FrameLayout(this);
        racine.setBackgroundColor(FOND);
        ecran = construireEcran();

        // Si l'app a planté la dernière fois, on montre pourquoi (pour pouvoir corriger).
        SharedPreferences p = getSharedPreferences("nutri", MODE_PRIVATE);
        String plantage = p.getString("plantage", null);
        if (plantage != null) {
            p.edit().remove("plantage").apply();
            racine.addView(ecran);
            setContentView(racine);
            afficher("La dernière ouverture a échoué. Envoie une capture de cet écran à Claude :\n\n"
                    + plantage.substring(0, Math.min(plantage.length(), 700)), true);
            return;
        }

        try {
            web = new WebView(this);
        } catch (Throwable t) {
            Log.e(TAG, "WebView indisponible", t);
            racine.addView(ecran);
            setContentView(racine);
            afficher("Le navigateur intégré d'Android n'est pas disponible sur ce téléphone. "
                    + "Mets à jour « Android System WebView » dans le Play Store, ou ouvre Nutri dans Chrome.", true);
            return;
        }
        web.setBackgroundColor(FOND);
        racine.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        racine.addView(ecran);
        setContentView(racine);
        afficher("Chargement de Nutri…", false);

        WebSettings r = web.getSettings();
        r.setJavaScriptEnabled(true);
        r.setDomStorageEnabled(true);
        r.setDatabaseEnabled(true);
        r.setMediaPlaybackRequiresUserGesture(false);
        r.setUseWideViewPort(true);
        r.setLoadWithOverviewMode(true);
        r.setJavaScriptCanOpenWindowsAutomatically(true);
        r.setAllowFileAccess(false);
        // Se présenter comme Chrome mobile : certains sites refusent les navigateurs intégrés.
        String ua = r.getUserAgentString();
        if (ua != null) r.setUserAgentString(ua.replace("; wv", "").replace(" Version/4.0", ""));

        CookieManager c = CookieManager.getInstance();
        c.setAcceptCookie(true);
        c.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri u = req.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                String s = u.getScheme() == null ? "" : u.getScheme();
                if (!s.startsWith("http")) {
                    ouvrirDehors(u);
                    return true;
                }
                if (h.endsWith("claude.ai") || h.endsWith("claude.com") || h.endsWith("anthropic.com")
                        || h.endsWith("claudeusercontent.com") || h.endsWith("cloudflare.com")) {
                    return false;
                }
                // Les autres liens (sources du Guide…) s'ouvrent dans le navigateur.
                ouvrirDehors(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(PLEIN_ECRAN, null);
                CookieManager.getInstance().flush();
                charge = true;
                cacher();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    afficher("Impossible d'ouvrir Nutri. Vérifie ta connexion internet, puis réessaie.", true);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progres) {
                if (progres >= 70) {
                    charge = true;
                    cacher();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rappel, FileChooserParams p) {
                if (rappelFichier != null) rappelFichier.onReceiveValue(null);
                rappelFichier = rappel;
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    choixEnAttente = p;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    return true;
                }
                return ouvrirChoix(p);
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> autoriser(req));
            }
        });

        // Si rien n'apparaît au bout de 20 secondes, on propose Chrome.
        minuterie.postDelayed(() -> {
            if (!charge) afficher("Ça met du temps… Tu peux attendre encore un peu, ou ouvrir Nutri dans Chrome.", true);
        }, 20000);

        if (etat != null) {
            web.restoreState(etat);
        } else {
            web.loadUrl(getString(R.string.url_app));
        }
    }

    /** Écran de chargement ou d'aide, par-dessus l'app. */
    private LinearLayout construireEcran() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(FOND);
        l.setPadding((int) (28 * d), (int) (28 * d), (int) (28 * d), (int) (28 * d));
        l.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView titre = new TextView(this);
        titre.setText("Nutri");
        titre.setTextColor(ROSE);
        titre.setTextSize(34);
        titre.setTypeface(Typeface.DEFAULT_BOLD);
        titre.setGravity(Gravity.CENTER);
        l.addView(titre);

        roue = new ProgressBar(this);
        LinearLayout.LayoutParams pr = new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d));
        pr.topMargin = (int) (20 * d);
        l.addView(roue, pr);

        message = new TextView(this);
        message.setTextColor(Color.parseColor("#F3E6EC"));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setTextIsSelectable(true);
        LinearLayout.LayoutParams pm = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pm.topMargin = (int) (18 * d);
        l.addView(message, pm);

        boutonReessayer = new Button(this);
        boutonReessayer.setText("Réessayer");
        boutonReessayer.setOnClickListener(v -> {
            if (web != null) {
                charge = false;
                afficher("Chargement de Nutri…", false);
                web.loadUrl(getString(R.string.url_app));
            } else {
                recreate();
            }
        });
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pb.topMargin = (int) (22 * d);
        l.addView(boutonReessayer, pb);

        boutonChrome = new Button(this);
        boutonChrome.setText("Ouvrir dans Chrome");
        boutonChrome.setOnClickListener(v -> ouvrirDehors(Uri.parse(getString(R.string.url_app))));
        l.addView(boutonChrome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private void afficher(String texte, boolean avecBoutons) {
        runOnUiThread(() -> {
            ecran.setVisibility(View.VISIBLE);
            message.setText(texte);
            roue.setVisibility(avecBoutons ? View.GONE : View.VISIBLE);
            boutonChrome.setVisibility(avecBoutons ? View.VISIBLE : View.GONE);
            boutonReessayer.setVisibility(avecBoutons ? View.VISIBLE : View.GONE);
        });
    }

    private void cacher() {
        runOnUiThread(() -> ecran.setVisibility(View.GONE));
    }

    private void ouvrirDehors(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Aucune app pour " + u);
        }
    }

    /** Garde la trace d'un plantage pour l'afficher au prochain lancement. */
    private void noterPlantages() {
        final Thread.UncaughtExceptionHandler avant = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((fil, t) -> {
            try {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                getSharedPreferences("nutri", MODE_PRIVATE).edit().putString("plantage", sw.toString()).commit();
            } catch (Throwable ignore) {
                // rien
            }
            if (avant != null) avant.uncaughtException(fil, t);
        });
    }

    /** Galerie ou appareil photo, au choix. */
    private boolean ouvrirChoix(WebChromeClient.FileChooserParams p) {
        Intent choisir = p.createIntent();
        Intent menu = Intent.createChooser(choisir, "Ajouter une photo");
        Intent cam = intentPhoto();
        if (cam != null) menu.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        try {
            startActivityForResult(menu, REQ_FICHIER);
            return true;
        } catch (ActivityNotFoundException e) {
            if (rappelFichier != null) rappelFichier.onReceiveValue(null);
            rappelFichier = null;
            return false;
        }
    }

    /** Photo prise directement avec l'appareil photo (si la permission est donnée). */
    private Intent intentPhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            File dossier = new File(getCacheDir(), "photos");
            dossier.mkdirs();
            File f = File.createTempFile("photo", ".jpg", dossier);
            photo = FileProvider.getUriForFile(this, getPackageName() + ".photos", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photo);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        } catch (Exception e) {
            photo = null;
            return null;
        }
    }

    /** Micro (dictée) et appareil photo demandés par la page. */
    private void autoriser(PermissionRequest req) {
        List<String> manquants = new ArrayList<>();
        for (String res : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                manquants.add(Manifest.permission.RECORD_AUDIO);
            }
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                manquants.add(Manifest.permission.CAMERA);
            }
        }
        if (manquants.isEmpty()) {
            req.grant(req.getResources());
        } else {
            demandeEnAttente = req;
            requestPermissions(manquants.toArray(new String[0]), REQ_DROITS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] droits, int[] resultats) {
        super.onRequestPermissionsResult(code, droits, resultats);
        if (code == REQ_CAMERA) {
            if (choixEnAttente != null) {
                WebChromeClient.FileChooserParams p = choixEnAttente;
                choixEnAttente = null;
                ouvrirChoix(p);
            }
            return;
        }
        if (code != REQ_DROITS || demandeEnAttente == null) return;
        boolean ok = true;
        for (int r : resultats) ok &= r == PackageManager.PERMISSION_GRANTED;
        if (ok) demandeEnAttente.grant(demandeEnAttente.getResources());
        else demandeEnAttente.deny();
        demandeEnAttente = null;
    }

    @Override
    protected void onActivityResult(int code, int resultat, Intent data) {
        super.onActivityResult(code, resultat, data);
        if (code != REQ_FICHIER || rappelFichier == null) return;
        Uri[] choix = null;
        if (resultat == RESULT_OK) {
            if (data == null || (data.getData() == null && data.getClipData() == null)) {
                if (photo != null) choix = new Uri[]{photo};
            } else {
                choix = WebChromeClient.FileChooserParams.parseResult(resultat, data);
            }
        }
        rappelFichier.onReceiveValue(choix);
        rappelFichier = null;
        photo = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle sortie) {
        super.onSaveInstanceState(sortie);
        if (web != null) web.saveState(sortie);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        minuterie.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
