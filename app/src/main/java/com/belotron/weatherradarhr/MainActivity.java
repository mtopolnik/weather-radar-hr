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
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static com.belotron.weatherradarhr.GifEditor.editGif;

public class MainActivity extends FragmentActivity {

    static final String LOGTAG = "WeatherRadar";
    private static final int BUFSIZ = 512;
    private static final int ANIMATION_COVERS_MINUTES = 100;

    private static final TabDescriptor[] tabs = {
            new TabDescriptor("Lisca",
                    "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                    10),
            new TabDescriptor("Puntijarka-Bilogora-Osijek",
                    "http://vrijeme.hr/kradar-anim.gif",
                    15),
//            new TabDescriptor("Dubrovnik",
//                    "http://vrijeme.hr/dradar-anim.gif",
//                    15),
    };


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
                case "imageFilename0":
                    m.appendReplacement(sb, tabs[0].filename());
                    break;
                case "imageFilename1":
                    m.appendReplacement(sb, tabs[1].filename());
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
            return 1; //tabs.length;
        }
    }

    public static class RadarImageFragment extends Fragment {

        @Override @Nullable
        public View onCreateView(LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState
        ) {
            final int tabIndex = getArguments().getInt("index");
            View rootView = inflater.inflate(R.layout.image_radar, container, false);
            final WebView webView = rootView.findViewById(R.id.web_view_radar);
            WebSettings s = webView.getSettings();
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            final AtomicInteger countdown = new AtomicInteger(tabs.length);
            for (final TabDescriptor desc : tabs) {
                Ion.with(getContext())
                        .load(desc.url)
                        .asByteArray()
                        .setCallback(new FutureCallback<byte[]>() {
                            @Override
                            public void onCompleted(Exception e, byte[] result) {
                                if (result == null) {
                                    Log.e(LOGTAG, "Couldn't load URL " + desc.url);
                                    return;
                                }
                                try {
                                    int frameDelay = (int) (1.2 * desc.minutesPerFrame);
                                    ByteBuffer buf = ByteBuffer.wrap(result);
                                    editGif(buf, frameDelay, desc.framesToKeep);
                                    final File gifFile = new File(getContext().getNoBackupFilesDir(), desc.filename());
                                    try (OutputStream out = new FileOutputStream(gifFile)) {
                                        out.write(buf.array(), buf.position(), buf.remaining());
                                    } catch (IOException e1) {
                                        throw new RuntimeException(e1);
                                    }
                                    if (countdown.addAndGet(-1) == 0) {
                                        String url = htmlFileForTab(tabIndex, getContext()).toURI().toString();
                                        webView.loadUrl(url);
                                    }
                                } catch (Throwable t) {
                                    Log.e(LOGTAG, "Error loading GIF " + desc.filename(), t);
//                                    throw t;
                                }
                            }
                        });
            }
            return rootView;
        }
    }

    private static final class TabDescriptor {
        final String title;
        final String url;
        final int minutesPerFrame;
        final int framesToKeep;

        TabDescriptor(String title, String url, int minutesPerFrame) {
            this.title = title;
            this.url = url;
            this.minutesPerFrame = minutesPerFrame;
            this.framesToKeep = (int) Math.ceil((double) ANIMATION_COVERS_MINUTES / minutesPerFrame);
        }

        String filename() {
            return url.substring(url.lastIndexOf('/') + 1, url.length());
        }
    }
}
