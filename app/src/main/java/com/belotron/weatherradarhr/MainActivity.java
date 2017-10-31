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
import static android.webkit.WebSettings.LOAD_NO_CACHE;
import static com.belotron.weatherradarhr.GifEditor.editGif;

public class MainActivity extends FragmentActivity {

    static final String LOGTAG = "WeatherRadar";

    static final int LOOP_COUNT = 20;
    static final int ANIMATION_DURATION = 300;

    private static final int BUFSIZ = 512;
    private static final int ANIMATION_COVERS_MINUTES = 100;

    private static final ImgDescriptor[] images = {
            new ImgDescriptor("Lisca",
                    "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                    10),
            new ImgDescriptor("Puntijarka-Bilogora-Osijek",
                    "http://vrijeme.hr/kradar-anim.gif",
                    15),
//            new ImgDescriptor("Dubrovnik",
//                    "http://vrijeme.hr/dradar-anim.gif",
//                    15),
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        ViewPager viewPager = findViewById(R.id.pager);
        viewPager.setAdapter(new FlipThroughRadarImages(getSupportFragmentManager()));
    }

    private static class FlipThroughRadarImages extends FragmentPagerAdapter {

        FlipThroughRadarImages(FragmentManager fm) {
            super(fm);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return images[position].title;
        }

        @Override
        public Fragment getItem(int i) {
            switch (i) {
                case 0:
                    return new RadarImageFragment();
                default:
                    throw new AssertionError("Invalid tab index: " + i);
            }
        }

        @Override
        public int getCount() {
            return 1;
        }
    }

    public static class RadarImageFragment extends Fragment {

        private WebView webView;

        @Override @Nullable
        public View onCreateView(LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState
        ) {
            View rootView = inflater.inflate(R.layout.image_radar, container, false);
            webView = rootView.findViewById(R.id.web_view_radar);
            WebSettings s = webView.getSettings();
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setCacheMode(LOAD_NO_CACHE);
            writeTabHtml();
            reloadImages();
            return rootView;
        }

        @Override
        public void onResume() {
            super.onResume();
            reloadImages();
        }

        private void reloadImages() {
            final AtomicInteger countdown = new AtomicInteger(images.length);
            for (final ImgDescriptor desc : images) {
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
                                   String url = tabHtmlFile(getContext()).toURI().toString();
                                   webView.clearCache(true);
                                   webView.loadUrl(url);
                               }
                           } catch (Throwable t) {
                               Log.e(LOGTAG, "Error loading GIF " + desc.filename(), t);
                           }
                       }
                   });
            }
        }

        private void writeTabHtml() {
            String htmlTemplate = readTemplate();
            File htmlFile = tabHtmlFile(getContext());
            try (Writer w = new BufferedWriter(new FileWriter(htmlFile))) {
                w.write(expandTemplate(htmlTemplate));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private String readTemplate() {
            try (InputStream is = getContext().getAssets().open("radar_image.html")) {
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

        private static String expandTemplate(String htmlTemplate) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(htmlTemplate);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String k = m.group(1);
                if (k.matches("imageFilename(\\d+)")) {
                    m.appendReplacement(sb, images[Integer.parseInt(k.substring(13, k.length()))].filename());
                } else {
                    throw new AssertionError("Invalid key in HTML template: " + k);
                }
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static File tabHtmlFile(Context context) {
            return new File(context.getNoBackupFilesDir(), "tab0.html");
        }
    }

    private static final class ImgDescriptor {
        final String title;
        final String url;
        final int minutesPerFrame;
        final int framesToKeep;

        ImgDescriptor(String title, String url, int minutesPerFrame) {
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
