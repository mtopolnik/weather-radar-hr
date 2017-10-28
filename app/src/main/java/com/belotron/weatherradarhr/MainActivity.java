package com.belotron.weatherradarhr;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import java.io.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static com.belotron.weatherradarhr.ModifyGifFramerate.modifyGifFramerate;

public class MainActivity extends FragmentActivity {

    private static final TabDescriptor[] tabs = {
            new TabDescriptor("Lisca",
                    "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                    10),
            new TabDescriptor("Puntijarka-Bilogora-Osijek",
                    "http://vrijeme.hr/kradar-anim.gif",
                    15),
            new TabDescriptor("Dubrovnik",
                    "http://vrijeme.hr/dradar-anim.gif",
                    15)
    };

    private static final int BUFSIZ = 512;
    private static final String LOGTAG = "WeatherRadar";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN);
        writeHtmlsForTabs();
        setContentView(R.layout.activity_main);
        ViewPager viewPager = findViewById(R.id.pager);
        viewPager.setAdapter(new FlipThroughRadarImages(getSupportFragmentManager()));
    }

    private void writeHtmlsForTabs() {
        String htmlTemplate = readTemplate();
        for (int i = 0; i < tabs.length; i++) {
            File htmlFile = htmlFileForTab(i, getApplicationContext());
            try (Writer w = new BufferedWriter(new FileWriter(htmlFile))) {
                w.write(expandTemplate(htmlTemplate, tabs[i]));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String expandTemplate(String htmlTemplate, TabDescriptor tab) {
        Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(htmlTemplate);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            switch (m.group(1)) {
                case "title":
                    m.appendReplacement(sb, tab.title);
                    break;
                case "imageFilename":
                    m.appendReplacement(sb, tab.filename());
                    break;
                default:
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String readTemplate() {
        try (InputStream is = getApplicationContext().getAssets().open("radar_image.html")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            char[] buf = new char[BUFSIZ];
            StringBuilder b = new StringBuilder(BUFSIZ);
            for (int count; (count = r.read(buf)) != -1;) {
                b.append(buf, 0, count);
            }
            return b.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File htmlFileForTab(int i, Context context) {
        return new File(context.getNoBackupFilesDir(), "tab" + i + ".html");
    }

    private static class FlipThroughRadarImages extends FragmentPagerAdapter {

        FlipThroughRadarImages(FragmentManager fm) {
            super(fm);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return tabs[position].title;
        }

        @Override
        public Fragment getItem(int i) {
            RadarImageFragment frag = new RadarImageFragment();
            Bundle args = new Bundle();
            args.putInt("index", i);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public int getCount() {
            return 3;
        }
    }

    public static class RadarImageFragment extends Fragment {

        @Override @Nullable
        public View onCreateView(LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState
        ) {
            final int tabIndex = getArguments().getInt("index");
            final TabDescriptor desc = tabs[tabIndex];
            Log.i(LOGTAG, desc.title);
            View rootView = inflater.inflate(R.layout.image_radar, container, false);
            final WebView webView = rootView.findViewById(R.id.web_view_radar);
            WebSettings s = webView.getSettings();
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            final File gifFile = new File(getContext().getNoBackupFilesDir(), desc.filename());
            Ion.with(getContext())
                    .load(desc.url)
                    .asByteArray()
                    .setCallback(new FutureCallback<byte[]>() {
                        @Override
                        public void onCompleted(Exception e, byte[] result) {
                            modifyGifFramerate(result, (int) (1.2 * tabs[tabIndex].minutesPerFrame), 120, 200);
                            try (OutputStream out = new FileOutputStream(gifFile)) {
                                out.write(result);
                            } catch (IOException e1) {
                                throw new RuntimeException(e1);
                            }
                            String url = htmlFileForTab(tabIndex, getContext()).toURI().toString();
                            Log.i(LOGTAG, url);
                            webView.loadUrl(url);
                        }
                    });
            return rootView;
        }
    }

    private static final class TabDescriptor {
        final String title;
        final String url;
        final int minutesPerFrame;

        TabDescriptor(String title, String url, int minutesPerFrame) {
            this.title = title;
            this.url = url;
            this.minutesPerFrame = minutesPerFrame;
        }

        String filename() {
            return url.substring(url.lastIndexOf('/') + 1, url.length());
        }
    }
}
