package com.belotron.weatherradarhr;

import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.TransformFuture;
import com.koushikdutta.ion.Ion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static com.belotron.weatherradarhr.ModifyGifFramerate.editGif;

public class MainActivity extends FragmentActivity {

    static final SparseArray<TabDescriptor> tabs = new SparseArray<>(); static {
        tabs.put(0, new TabDescriptor(
                "Lisca",
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif"
        ));
        tabs.put(1, new TabDescriptor(
                "Puntijarka-Bilogora-Osijek",
                "http://vrijeme.hr/kradar-anim.gif"
        ));
        tabs.put(2, new TabDescriptor(
                "Dubrovnik",
                "http://vrijeme.hr/dradar-anim.gif"
        ));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            return tabs.get(position).title;
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
            final TabDescriptor desc = tabs.get(getArguments().getInt("index"));
            Log.i("RadarImageFragment", desc.title);
            View rootView = inflater.inflate(R.layout.image_radar, container, false);
            final ImageView imgView = rootView.findViewById(R.id.image_view_radar);
            final File gifFile = new File(getContext().getNoBackupFilesDir(), desc.title + ".gif");
            Ion.with(getContext())
                    .load(desc.url)
                    .asByteArray()
                    .setCallback(new FutureCallback<byte[]>() {
                        @Override
                        public void onCompleted(Exception e, byte[] result) {
                            editGif(ByteBuffer.wrap(result), 10, 120, 200);
                            try (OutputStream out = new FileOutputStream(gifFile)) {
                                Log.i("RadarImageFragment", "out.write(result)");
                                out.write(result);
                                Log.i("RadarImageFragment", "out.write(result) success");
                            } catch (IOException e1) {
                                throw new RuntimeException(e1);
                            }
                            Ion.with(RadarImageFragment.this)
                                    .load(gifFile)
                                    .intoImageView(imgView);
                        }
                    });
            return rootView;
        }
    }

    private static final class TabDescriptor {
        final String title;
        final String url;

        TabDescriptor(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }
}
