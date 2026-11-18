package com.marjoaissi.nutri;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

import java.util.Arrays;

/**
 * Ouvre l'app Nutri (hébergée dans Claude) dans un onglet Chrome intégré.
 * On garde la connexion Claude de Chrome : l'assistante IA, la dictée et les photos
 * marchent comme dans le navigateur, et les données sont les mêmes partout.
 * Quand on revient en arrière depuis l'app, le lanceur se ferme.
 */
public class MainActivity extends Activity {

    private static final String CLE_OUVERT = "ouvert";
    private boolean ouvert = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        if (etat != null) {
            ouvert = etat.getBoolean(CLE_OUVERT, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ouvert) {
            ouvert = true;
            ouvrir();
        } else {
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle sortie) {
        super.onSaveInstanceState(sortie);
        sortie.putBoolean(CLE_OUVERT, ouvert);
    }

    private void ouvrir() {
        Uri adresse = Uri.parse(getString(R.string.url_app));
        int rose = getResources().getColor(R.color.nutri_rose, getTheme());

        CustomTabsIntent onglet = new CustomTabsIntent.Builder()
                .setShowTitle(false)
                .setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(rose)
                        .setNavigationBarColor(rose)
                        .build())
                .build();

        // Chrome d'abord : c'est là que la dictée et la connexion Claude marchent le mieux.
        String navigateur = CustomTabsClient.getPackageName(this,
                Arrays.asList("com.android.chrome", "com.chrome.beta", "com.sec.android.app.sbrowser"), true);
        if (navigateur != null) {
            onglet.intent.setPackage(navigateur);
        }

        try {
            onglet.launchUrl(this, adresse);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, adresse));
            } catch (ActivityNotFoundException e2) {
                finish();
            }
        }
    }
}
