package com.marjoaissi.nutri;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Nutri en plein écran : l'app (hébergée dans Claude) s'affiche dans un navigateur intégré,
 * sans barre d'adresse ni en-tête Claude. La connexion Claude se fait une fois, dans l'app.
 */
public class MainActivity extends Activity {

    private static final int REQ_FICHIER = 1;
    private static final int REQ_DROITS = 2;
    private static final int REQ_CAMERA = 3;
    private static final int FOND = Color.parseColor("#14080F");

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
    private ValueCallback<Uri[]> rappelFichier;
    private Uri photo;
    private PermissionRequest demandeEnAttente;
    private WebChromeClient.FileChooserParams choixEnAttente;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        Window w = getWindow();
        w.setStatusBarColor(FOND);
        w.setNavigationBarColor(FOND);

        web = new WebView(this);
        web.setBackgroundColor(FOND);
        setContentView(web);

        WebSettings r = web.getSettings();
        r.setJavaScriptEnabled(true);
        r.setDomStorageEnabled(true);
        r.setDatabaseEnabled(true);
        r.setMediaPlaybackRequiresUserGesture(false);
        r.setUseWideViewPort(true);
        r.setLoadWithOverviewMode(true);
        r.setJavaScriptCanOpenWindowsAutomatically(true);
        r.setAllowFileAccess(false);

        CookieManager c = CookieManager.getInstance();
        c.setAcceptCookie(true);
        c.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri u = req.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                if (h.endsWith("claude.ai") || h.endsWith("claude.com") || h.endsWith("anthropic.com")
                        || h.endsWith("claudeusercontent.com") || h.endsWith("cloudflare.com")) {
                    return false;
                }
                // Les autres liens (sources du Guide…) s'ouvrent dans le navigateur.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(PLEIN_ECRAN, null);
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rappel, FileChooserParams p) {
                if (rappelFichier != null) rappelFichier.onReceiveValue(null);
                rappelFichier = rappel;
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    // On demande l'appareil photo une fois, puis on ouvre le choix.
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

        if (etat != null) {
            web.restoreState(etat);
        } else {
            web.loadUrl(getString(R.string.url_app));
        }
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
}
