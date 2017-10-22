package com.belotron.weatherradarhr;

import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.koushikdutta.ion.Ion;

public class MainActivity extends FragmentActivity {

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
            return titleFor(position);
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Fragment getItem(int i) {
            RadarImageFragment frag = new RadarImageFragment();
            Bundle args = new Bundle();
            args.putString("url", urlFor(i));
            frag.setArguments(args);
            return frag;
        }

        private static String titleFor(int i) {
            return i == 0 ? "Radar Lisca"
                 : i == 1 ? "Rader Bilogora"
                 : throwIllegalArgument(i);
        }

        private static String urlFor(int i) {
            return i == 0 ? "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif"
                 : i == 1 ? "http://vrijeme.hr/bradar-anim.gif"
                 : throwIllegalArgument(i);
        }

        private static String throwIllegalArgument(int i) {
            throw new IllegalArgumentException("Fragment index out of range: " + i);
        }
    }

    public static class RadarImageFragment extends Fragment {

        @Override @Nullable
        public View onCreateView(LayoutInflater inflater,
                                 @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState
        ) {
            View rootView = inflater.inflate(R.layout.radar_image, container, false);
            ImageView imgView = rootView.findViewById(R.id.image_view_radar);
            Bundle args = getArguments();
            Ion.with(imgView)
                    .placeholder(R.drawable.rectangle)
                    .load(args.getString("url"));
            return imgView;
        }
    }
}
