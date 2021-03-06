package com.xw.project.gracefulmovies.service;

import android.Manifest;
import android.app.IntentService;
import android.arch.lifecycle.LiveData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xw.project.gracefulmovies.GMApplication;
import com.xw.project.gracefulmovies.data.api.ApiObserver;
import com.xw.project.gracefulmovies.data.ao.NetLocResult;
import com.xw.project.gracefulmovies.data.db.entity.CityEntity;
import com.xw.project.gracefulmovies.repository.LocationRepository;
import com.xw.project.gracefulmovies.rx.RxSchedulers;
import com.xw.project.gracefulmovies.rx.SimpleConsumer;
import com.xw.project.gracefulmovies.util.Logy;
import com.xw.project.gracefulmovies.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

/**
 * 定位服务
 * <p>
 * 由于本项目对位置精度要求不高，故采用网络接口转换经纬度得到位置信息，既减小apk大小，又可适配所有手机
 * 转换api来自阿里地图开发平台（大厂表示你们随便用，挤爆算我输<(￣︶￣)>）：
 * http://gc.ditu.aliyun.com/regeocoding?l=39.938133,116.395739&type=010
 * <p>
 * 手机GPS采集的是WGS84坐标系，需要通过偏移算法转为国测局的GCJ02坐标（俗称火星坐标，貌似国产地图除百度外都是
 * 这个坐标系），算法来源地址：
 * https://gist.github.com/jp1017/71bd0976287ce163c11a7cb963b04dd8，感谢原作者
 * <p/>
 * Created by woxingxiao on 2018-08-18.
 */
public class LocationService extends IntentService {

    // 直辖市、省会城市
    private static final String CAPITAL_CITIES = "北京，天津，上海，重庆，石家庄，太原，呼和浩特，沈阳，" +
            "长春，哈尔滨，南京，杭州，合肥，福州，南昌，济南，郑州，武汉，长沙，广州，南宁，海口，成都，" +
            "贵阳，昆明，西安，兰州，西宁，拉萨，银川，乌鲁木齐";
    private static final double pi = 3.1415926535897932384626; // π
    private static final double a = 6378245.0; // 长半轴
    private static final double ee = 0.00669342162296594323; // 扁率

    private LocationManager mLocationManager;
    private LocationRepository mRepository;
    private CompositeDisposable mDisposable;
    private boolean isRelocate;

    public static void start(Context context) {
        context.startService(new Intent(context, LocationService.class));
    }

    public static void relocate(Context context) {
        Intent intent = new Intent(context, LocationService.class);
        intent.putExtra("relocate", true);
        context.startService(intent);
    }

    public LocationService() {
        super("LocationService");
    }

    @Override
    public void onStart(@Nullable Intent intent, int startId) {
        super.onStart(intent, startId);
        Logy.w("LocationService", "================ onStart ===============");

        if (intent != null) {
            isRelocate = intent.getBooleanExtra("relocate", false);
        }
        initLocation();
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
    }

    private void initLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (mLocationManager == null)
            return;

        List<String> providers = mLocationManager.getProviders(true);

        String locationProvider;
        /**
         * 如果首选GPS定位，会存在这种情况，上次GPS启动采集数据在A地，本次在B地需要定位，但用户恰好在室内无
         * GPS信号，只好使用上次定位数据，就出现了地区级偏差。而网络定位则更具有实时性，在精度要求不高以及室内
         * 使用场景更多的前提下，首选网络定位
         */
        if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
            locationProvider = LocationManager.NETWORK_PROVIDER; // 首选网络定位
        } else if (providers.contains(LocationManager.GPS_PROVIDER)) {
            locationProvider = LocationManager.GPS_PROVIDER;
        } else {
            locationProvider = LocationManager.PASSIVE_PROVIDER;
        }

        if (mLocationListener != null)
            mLocationManager.requestLocationUpdates(locationProvider, 2000, 10, mLocationListener);
    }

    private LocationListener mLocationListener = new LocationListener() {

        @Override
        public void onLocationChanged(Location location) {
            if (mLocationListener == null)
                return;
            if (location == null)
                return;

            if (mRepository == null) {
                mRepository = new LocationRepository();
            }
            if (mDisposable == null) {
                mDisposable = new CompositeDisposable();
            } else {
                mDisposable.clear();
            }

            Logy.i("LocationService", "------------ 原生经纬度：" + location.getLatitude() + "," + location.getLongitude());
            double[] latLng = WGS84ToGCJ02(location.getLatitude(), location.getLongitude());
            Logy.i("LocationService", "---------- 转换后经纬度：" + latLng[0] + "," + latLng[1]);

            Disposable disposable = mRepository.fetchLocationInfo(latLng[0] + "," + latLng[1])
                    .compose(RxSchedulers.applyIO())
                    .subscribeWith(new ApiObserver<NetLocResult>() {
                        @Override
                        public void onNext(NetLocResult netLocResult) {
                            if (netLocResult.getUpperCity() != null && !netLocResult.getUpperCity().isEmpty()) {
                                String cityName = Util.trimCity(netLocResult.getUpperCity());

                                LiveData<CityEntity> liveData = GMApplication.getInstance().getCityRepository().getUpperCity();
                                CityEntity city = liveData.getValue();
                                boolean diffUpper = city == null || !cityName.equals(city.getName());
                                if (diffUpper) {
                                    city = new CityEntity();
                                    city.setUpper(true);
                                    city.setId(findIdByCityName(cityName));
                                    city.setName(cityName);
                                    GMApplication.getInstance().getCityRepository().updateUpperCity(city);
                                }

                                /**
                                 * 非直辖市和省会城市，使用定位城市的下级城市
                                 */
                                if (!CAPITAL_CITIES.contains(Util.trimCity(cityName))) {
                                    cityName = Util.trimCity(netLocResult.getCity());
                                }
                                Logy.i("LocationService", "------------定位成功：" + netLocResult.getUpperCity() + "，" + cityName);

                                liveData = GMApplication.getInstance().getCityRepository().getCity();
                                city = liveData.getValue();
                                if (diffUpper && (city == null || !cityName.equals(city.getName()))) {
                                    city = new CityEntity();
                                    city.setId(findIdByCityName(cityName));
                                    city.setName(cityName);
                                    GMApplication.getInstance().getCityRepository().updateCity(city);
                                }

                                if (isRelocate) {
                                    Observable.just(cityName)
                                            .compose(RxSchedulers.apply())
                                            .subscribe(new SimpleConsumer<String>() {
                                                @Override
                                                public void accept(String it) {
                                                    Toast.makeText(LocationService.this, "定位成功：" + it, Toast.LENGTH_LONG).show();
                                                }
                                            });
                                }
                            }
                        }

                        @Override
                        protected void onErrorResolved(Throwable e, String msg) {
                            Observable.just("")
                                    .compose(RxSchedulers.apply())
                                    .subscribe(new SimpleConsumer<String>() {
                                        @Override
                                        public void accept(String it) {
                                            Toast.makeText(LocationService.this, "定位失败，" + msg, Toast.LENGTH_LONG).show();
                                        }
                                    });
                        }

                        @Override
                        public void onFinally() {
                            stopLocation();
                        }
                    });
            mDisposable.add(disposable);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private void stopLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (mLocationListener != null) {
            mLocationManager.removeUpdates(mLocationListener);
            mLocationListener = null;
        }
    }

    /**
     * WGS84转GCJ02(火星坐标系)
     *
     * @param lng WGS84坐标系的经度
     * @param lat WGS84坐标系的纬度
     * @return 火星坐标数组
     */
    private double[] WGS84ToGCJ02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lat - 35.0, lng - 105.0);
        double dLng = transformLng(lat - 35.0, lng - 105.0);
        double radLat = lat / 180.0 * pi;
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi);
        dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * pi);
        double mgLat = lat + dLat;
        double mgLng = lng + dLng;
        return new double[]{mgLat, mgLng};
    }

    /**
     * 纬度转换
     */
    private double transformLat(double lat, double lng) {
        double ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * pi) + 20.0 * Math.sin(2.0 * lng * pi)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * pi) + 40.0 * Math.sin(lat / 3.0 * pi)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * pi) + 320 * Math.sin(lat * pi / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * 经度转换
     */
    private double transformLng(double lat, double lng) {
        double ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * pi) + 20.0 * Math.sin(2.0 * lng * pi)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * pi) + 40.0 * Math.sin(lng / 3.0 * pi)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * pi) + 300.0 * Math.sin(lng / 30.0 * pi)) * 2.0 / 3.0;
        return ret;
    }

    private boolean outOfChina(double lat, double lng) {
        if (lng < 72.004 || lng > 137.8347) {
            return true;
        } else if (lat < 0.8293 || lat > 55.8271) {
            return true;
        }
        return false;
    }

    private int findIdByCityName(String name) {
        StringBuilder sBuilder = new StringBuilder();
        try {
            AssetManager assetManager = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assetManager.open("city.json")));
            String line;
            while ((line = bf.readLine()) != null) {
                sBuilder.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        ArrayList<CityEntity> list = new Gson().fromJson(sBuilder.toString(),
                new TypeToken<List<CityEntity>>() {
                }.getType()
        );

        int id = 880;
        if (list != null && !list.isEmpty()) {
            for (CityEntity city : list) {
                if (city.getName().equals(name)) {
                    id = city.getId();
                    break;
                }
            }
        }

        return id;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logy.w("LocationService", "=============== onDestroy ==============");
    }

//    private void task() {
//        Observable.just("")
//                .flatMap(new Func1<String, Observable<ArrayList<CityModel>>>() {
//                    @Override
//                    public Observable<ArrayList<CityModel>> call(String s) {
//                        StringBuilder sBuilder = new StringBuilder();
//                        try {
//                            AssetManager assetManager = getAssets();
//                            BufferedReader bf = new BufferedReader(new InputStreamReader(assetManager.open("city.json")));
//                            String line;
//                            while ((line = bf.readLine()) != null) {
//                                sBuilder.append(line);
//                            }
//                        } catch (IOException exception) {
//                            exception.printStackTrace();
//                        }
//
//                        ArrayList<CityModel> list = new Gson().fromJson(sBuilder.toString(), new TypeToken<List<CityModel>>() {
//                        }.getType());
//
//                        return Observable.just(list);
//                    }
//                })
//                .map(new Func1<ArrayList<CityModel>, Boolean>() {
//                    @Override
//                    public Boolean call(ArrayList<CityModel> cityModels) {
//                        BufferedWriter writer = null;
//
//                        try {
//                            boolean ok = true;
//
//                            File file = new File(Environment.getExternalStorageDirectory() + "/city.json");
//                            if (!file.exists()) {
//                                ok = file.createNewFile();
//                            }
//
//                            if (!ok) {
//                                return false;
//                            }
//
//                            writer = new BufferedWriter(new FileWriter(file));
//                            writer.write(new Gson().toJson(cityModels));
//
//                            return true;
//                        } catch (Exception exception) {
//                            //
//                        } finally {
//                            if (writer != null) {
//                                try {
//                                    writer.close();
//                                } catch (IOException exception) {
//                                    exception.printStackTrace();
//                                }
//                            }
//                        }
//
//                        return false;
//                    }
//                })
//                .subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(new Action1<Boolean>() {
//                    @Override
//                    public void call(Boolean aBoolean) {
//                        Log.d("", "成功");
//                    }
//                });
//    }
}
