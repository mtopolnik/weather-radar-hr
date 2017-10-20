package com.belotron.weatherradarhr;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import com.koushikdutta.ion.Ion;

public class RadarLisca extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radar_lisca);
        ImageView imgView = (ImageView) findViewById(R.id.imageView);
        Ion.with(imgView)
                .placeholder(R.drawable.rectangle)
                .load("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif");
    }
}
