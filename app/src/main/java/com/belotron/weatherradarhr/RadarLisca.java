package com.belotron.weatherradarhr;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.webkit.WebView;

public class RadarLisca extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radar_lisca);
        WebView wv = (WebView) findViewById(R.id.liscaWeb);
        wv.loadUrl("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif");
    }
}
