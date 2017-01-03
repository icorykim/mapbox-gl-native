package com.mapbox.mapboxsdk.maps.widgets;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;

import com.mapbox.mapboxsdk.R;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.constants.MyBearingTracking;
import com.mapbox.mapboxsdk.constants.MyLocationTracking;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.location.LocationListener;
import com.mapbox.mapboxsdk.location.LocationServices;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.style.layers.FillLayer;
import com.mapbox.mapboxsdk.style.layers.Layer;
import com.mapbox.mapboxsdk.style.layers.Property;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;
import com.mapbox.mapboxsdk.style.sources.Source;
import com.mapbox.services.commons.geojson.Feature;
import com.mapbox.services.commons.geojson.FeatureCollection;
import com.mapbox.services.commons.geojson.MultiPoint;
import com.mapbox.services.commons.geojson.Polygon;
import com.mapbox.services.commons.models.Position;
import com.mapbox.services.commons.turf.TurfConstants;
import com.mapbox.services.commons.turf.TurfException;
import com.mapbox.services.commons.turf.TurfMeasurement;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.mapbox.mapboxsdk.constants.MyLocationConstants.ACCURACY_LAYER;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.LOCATION_LAYER;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.LOCATION_LAYER_BACKGROUND;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.USER_LOCATION_ACCURACY_SOURCE;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.USER_LOCATION_ARROW_ICON;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.USER_LOCATION_ICON;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.USER_LOCATION_ICON_BACKGROUND;
import static com.mapbox.mapboxsdk.constants.MyLocationConstants.USER_LOCATION_SOURCE;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillOpacity;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconRotationAlignment;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.visibility;

public class MyLocation {

  // TODO port Turf Circle to MAS
  // TODO Check if code works in edit mode (I removed isInEditMode())

  private static final int COMPASS_UPDATE_RATE_MS = 750;
  private static final int STALE_USER_LOCATION_MS = 10000;

  private MyLocationBehavior myLocationBehavior;
  private GpsLocationListener userLocationListener;
  private CompassListener compassListener;
  private Context context;
  private Source userLocationSource;

  private ValueAnimator accuracyAnimator;
  private ValueAnimator directionAnimator;
  private ValueAnimator locationChangeAnimator;
  private long locationUpdateTimestamp;

  private Handler staleHandler;
  private Runnable staleRunnable;
  public boolean isStale;

  private int tintColor;
  private int staleStateTintColor;
  private int accuracyTintColor;

  private LatLng latLng;
  private Location location;
  private float accuracy;
  private float previousDirection;

  private double bearing;
  private float magneticHeading;

  private Matrix matrix;
  private Camera camera;

  private MapboxMap mapboxMap;

  @MyLocationTracking.Mode
  private int myLocationTrackingMode;

  @MyBearingTracking.Mode
  private int myBearingTrackingMode;

  public MyLocation(Context context) {
    this.context = context;
    init();
  }

  private void init() {

   // toggleGps(false);

    matrix = new Matrix();
    camera = new Camera();
    camera.setLocation(0, 0, -1000);

    myLocationBehavior = new MyLocationBehaviorFactory().getBehavioralModel(MyLocationTracking.TRACKING_NONE);
    compassListener = new CompassListener(context);

    tintColor = Color.parseColor("#4C9AF2");
    staleStateTintColor = Color.parseColor("#C8C6C6");
    accuracyTintColor = Color.parseColor("#4C9AF2");
  }

  private void addLayers() {
    if (mapboxMap.getLayer(ACCURACY_LAYER) == null) {
      FillLayer accuracyLayer = new FillLayer(ACCURACY_LAYER, USER_LOCATION_ACCURACY_SOURCE).withProperties(
        fillColor(accuracyTintColor),
        fillOpacity(0.3f)
      );
      mapboxMap.addLayer(accuracyLayer);
    }

    if (mapboxMap.getLayer(LOCATION_LAYER) == null) {

      // Add the location icon image to the map
      Drawable drawable = ContextCompat.getDrawable(context, R.drawable.mapbox_user_icon);
      Drawable bearingDrawable = ContextCompat.getDrawable(context, R.drawable.mapbox_user_icon_arrow);
      mapboxMap.getMyLocationViewSettings().setForegroundDrawable(drawable, bearingDrawable);

      Drawable userLocationDrawableBackground = ContextCompat.getDrawable(context, R.drawable.mapbox_user_icon_background);
      mapboxMap.getMyLocationViewSettings().setBackgroundDrawable(userLocationDrawableBackground);

      SymbolLayer locationLayerBackground = new SymbolLayer(LOCATION_LAYER_BACKGROUND, USER_LOCATION_SOURCE).withProperties(
        iconImage(myBearingTrackingMode == MyBearingTracking.NONE ? USER_LOCATION_ICON_BACKGROUND : USER_LOCATION_ARROW_ICON),
        iconAllowOverlap(true),
        iconIgnorePlacement(true),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
      );
      mapboxMap.addLayer(locationLayerBackground);

      SymbolLayer locationLayer = new SymbolLayer(LOCATION_LAYER, USER_LOCATION_SOURCE).withProperties(
        iconImage(myBearingTrackingMode == MyBearingTracking.NONE ? USER_LOCATION_ICON : USER_LOCATION_ARROW_ICON),
        iconAllowOverlap(true),
        iconIgnorePlacement(true),
        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
      );
      mapboxMap.addLayer(locationLayer);
    }
  }

  //TODO move to utils?
  private static Bitmap getBitmapFromDrawable(Drawable drawable) {
    if (drawable instanceof BitmapDrawable) {
      return ((BitmapDrawable) drawable).getBitmap();
    } else {
      Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(),
        Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
      drawable.draw(canvas);
      return bitmap;
    }
  }

  public void setCameraPosition(CameraPosition position) {
    // if (position != null) {
    //    setTilt(position.tilt);
    //     setBearing(position.bearing);
    //  }
  }

  public void onStart() {
    if (myBearingTrackingMode == MyBearingTracking.COMPASS) {
      compassListener.onResume();
    }
    // TODO check if location was enabled
//    if (isEnabled()) {
      toggleGps(true);
   // }
  }

  public void onStop() {
    compassListener.onPause();
    toggleGps(false);
  }

  public void setMapboxMap(MapboxMap mapboxMap) {
    this.mapboxMap = mapboxMap;
  }

  public Context getContext() {
    return context;
  }

  /**
   * Enabled / Disable GPS location updates along with updating the UI
   *
   * @param enableGps true if GPS is to be enabled, false if GPS is to be disabled
   */
  // TODO rename to enable?
  public void toggleGps(boolean enableGps) {
    LocationServices locationServices = LocationServices.getLocationServices(context);
    if (enableGps) {
      // Set an initial location if one available
      Location lastLocation = locationServices.getLastLocation();

      if (lastLocation != null) {
        setLocation(lastLocation);
      }

      if (userLocationListener == null) {
        userLocationListener = new MyLocation.GpsLocationListener(this);
      }

      locationServices.addLocationListener(userLocationListener);
      addLayers();
    } else {
      // Disable location and user dot
      location = null;
      locationServices.removeLocationListener(userLocationListener);
    }

    Layer accuracy = mapboxMap.getLayer(ACCURACY_LAYER);
    if (accuracy != null) {
      accuracy.setProperties(visibility(enableGps ? Property.VISIBLE : Property.NONE));
    }

    Layer locationIcon = mapboxMap.getLayer(LOCATION_LAYER);
    if (locationIcon != null) {
      locationIcon.setProperties(visibility(enableGps ? Property.VISIBLE : Property.NONE));
    }

    locationServices.toggleGPS(enableGps);
  }

  public Location getLocation() {
    return location;
  }

  public void setLocation(Location location) {
    if (location == null) {
      this.location = null;
      return;
    }

    this.location = location;
    myLocationBehavior.updateLatLng(location);
  }

  public void setMyBearingTrackingMode(@MyBearingTracking.Mode int myBearingTrackingMode) {
    // Change the icon image to display arrow
    if (mapboxMap.getLayer(LOCATION_LAYER) != null) {
      mapboxMap.getLayer(LOCATION_LAYER).setProperties(
        iconImage(myBearingTrackingMode == MyBearingTracking.NONE ? USER_LOCATION_ICON : USER_LOCATION_ARROW_ICON)
      );
    }
    this.myBearingTrackingMode = myBearingTrackingMode;
    if (myBearingTrackingMode == MyBearingTracking.COMPASS) {
      compassListener.onResume();
    } else {
      compassListener.onPause();
      if (myLocationTrackingMode == MyLocationTracking.TRACKING_FOLLOW) {
        // always face north
        setCompass(0);
      }
    }
  }

//  private class MarkerCoordinateAnimatorListener implements ValueAnimator.AnimatorUpdateListener {
//
//    private MyLocationBehavior behavior;
//    private double fromLat;
//    private double fromLng;
//    private double toLat;
//    private double toLng;
//
//    private MarkerCoordinateAnimatorListener(MyLocationBehavior myLocationBehavior, LatLng from, LatLng to) {
//      behavior = myLocationBehavior;
//      fromLat = from.getLatitude();
//      fromLng = from.getLongitude();
//      toLat = to.getLatitude();
//      toLng = to.getLongitude();
//    }
//
//    @Override
//    public void onAnimationUpdate(ValueAnimator animation) {
//      float frac = animation.getAnimatedFraction();
//      double latitude = fromLat + (toLat - fromLat) * frac;
//      double longitude = fromLng + (toLng - fromLng) * frac;
//      behavior.updateLatLng(latitude, longitude);
//      behavior.updateAccuracy(latitude, longitude);
//    }
//  }

  public void setMyLocationTrackingMode(@MyLocationTracking.Mode int myLocationTrackingMode) {
    MyLocationBehaviorFactory factory = new MyLocationBehaviorFactory();
    myLocationBehavior = factory.getBehavioralModel(myLocationTrackingMode);

    if (location != null) {
      if (myLocationTrackingMode == MyLocationTracking.TRACKING_FOLLOW) {
        // center map directly
        mapboxMap.easeCamera(CameraUpdateFactory.newLatLng(new LatLng(location)), 0, false /*linear interpolator*/,
          false /*do not disable tracking*/, null);
      } else {
        // do not use interpolated location from tracking mode
        latLng = null;
      }
      myLocationBehavior.updateLatLng(location);
    }

    this.myLocationTrackingMode = myLocationTrackingMode;
  }

  @MyLocationTracking.Mode
  public int getMyLocationTrackingMode() {
    return myLocationTrackingMode;
  }


  @MyBearingTracking.Mode
  public int getMyBearingTrackingMode() {
    return myBearingTrackingMode;
  }

  private void setCompass(double bearing) {
    setCompass(bearing, 0 /* no animation */);
  }

  private void setCompass(double bearing, long duration) {
    float oldDir = previousDirection;
    if (directionAnimator != null) {
      oldDir = (Float) directionAnimator.getAnimatedValue();
      directionAnimator.end();
      directionAnimator = null;
    }

    float newDir = (float) bearing;
    // No visible change occurred
    if (Math.abs(newDir - oldDir) < 1) {
      return;
    }
    float diff = oldDir - newDir;
    if (diff > 180.0f) {
      newDir += 360.0f;
    } else if (diff < -180.0f) {
      newDir -= 360.f;
    }
    previousDirection = newDir;
    directionAnimator = ValueAnimator.ofFloat(oldDir, newDir);
    directionAnimator.setDuration(duration);
    directionAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
      @Override
      public void onAnimationUpdate(ValueAnimator valueAnimator) {
        mapboxMap.getLayer(LOCATION_LAYER).setProperties(
          PropertyFactory.iconRotate((float) valueAnimator.getAnimatedValue())
        );
      }
    });
    directionAnimator.start();
  }

  private static class GpsLocationListener implements LocationListener {

    private WeakReference<MyLocation> userLocation;

    GpsLocationListener(MyLocation myLocation) {
      userLocation = new WeakReference<>(myLocation);
    }

    /**
     * Callback method for receiving location updates from LocationServices.
     *
     * @param location The new Location data
     */
    @Override
    public void onLocationChanged(Location location) {
      MyLocation myLocation = userLocation.get();
      if (myLocation != null) {
        myLocation.setLocation(location);
      }
    }
  }

  private class CompassListener implements SensorEventListener {

    private final SensorManager sensorManager;

    private Sensor rotationVectorSensor;
    float[] matrix = new float[9];
    float[] orientation = new float[3];

    // Compass data
    private long compassUpdateNextTimestamp = 0;

    CompassListener(Context context) {
      sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
      rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    public void onResume() {
      sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void onPause() {
      sensorManager.unregisterListener(this, rotationVectorSensor);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

      // check when the last time the compass was updated, return if too soon.
      long currentTime = SystemClock.elapsedRealtime();
      if (currentTime < compassUpdateNextTimestamp) {
        return;
      }

      if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {

        // calculate the rotation matrix
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, orientation);

        magneticHeading = (float) Math.toDegrees(SensorManager.getOrientation(matrix, orientation)[0]);
        if (myLocationTrackingMode == MyLocationTracking.TRACKING_FOLLOW) {
          rotateCamera(magneticHeading);
        }
        // Change compass direction
        setCompass(magneticHeading - bearing, COMPASS_UPDATE_RATE_MS);

        compassUpdateNextTimestamp = currentTime + COMPASS_UPDATE_RATE_MS;
      }
    }

    private void rotateCamera(float rotation) {
      CameraPosition.Builder builder = new CameraPosition.Builder();
      builder.bearing(rotation);
      mapboxMap.easeCamera(CameraUpdateFactory.newCameraPosition(builder.build()), COMPASS_UPDATE_RATE_MS,
        false /*linear interpolator*/, false /*do not disable tracking*/, null);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

  }

  // https://github.com/mapbox/mapbox-gl-style-spec/issues/459#issuecomment-224957055
  private FeatureCollection createGeoJSONCircle(Position center, double radius) throws TurfException {
    // turf circle
    int steps = 64;
    List<Position> coordinates = new ArrayList<>();

    for (int i = 0; i < steps; i++) {
      coordinates.add(TurfMeasurement.destination(center, radius, i * 360 / steps, TurfConstants.UNIT_METERS));
    }

    List<List<Position>> a = new ArrayList<>();
    a.add(coordinates);
    Polygon polygon = Polygon.fromCoordinates(a);
    return FeatureCollection.fromFeatures(new Feature[] {Feature.fromGeometry(polygon)});
  }

  private class MyLocationBehaviorFactory {

    MyLocationBehavior getBehavioralModel(@MyLocationTracking.Mode int mode) {
      if (mode == MyLocationTracking.TRACKING_NONE) {
        return new MyLocationShowBehavior();
      } else {
        return new MyLocationTrackingBehavior();
      }
    }
  }

  private abstract class MyLocationBehavior {

    void updateLatLng(@NonNull Location newLocation) {
      location = newLocation;

      if (isStale) {
        final Drawable drawable = ContextCompat.getDrawable(context, R.drawable.mapbox_user_icon);
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.SRC_IN);


        ValueAnimator colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), Color.parseColor("#C8C6C6"), tintColor);
        colorAnimator.setDuration(500);
        colorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
          @Override
          public void onAnimationUpdate(ValueAnimator valueAnimator) {
            DrawableCompat.setTint(drawable, (int) valueAnimator.getAnimatedValue());
            Bitmap icon = getBitmapFromDrawable(drawable);
            mapboxMap.addImage(USER_LOCATION_ICON, icon);
          }
        });
        colorAnimator.start();
      }
      isStale = false;

      MultiPoint multiPoint = MultiPoint.fromCoordinates(new double[][]{new double[]{location.getLongitude(), location.getLatitude()}});
      FeatureCollection featureCollection = FeatureCollection.fromFeatures(new Feature[] {Feature.fromGeometry(multiPoint)});
      GeoJsonSource source = mapboxMap.getSourceAs(USER_LOCATION_SOURCE);
      if (source != null) {
        source.setGeoJson(featureCollection);
      } else {
        userLocationSource = new GeoJsonSource(USER_LOCATION_SOURCE, featureCollection);
        mapboxMap.addSource(userLocationSource);
      }

      if (staleHandler != null && staleRunnable != null) {
        staleHandler.removeCallbacks(staleRunnable);
      }
      staleHandler = new Handler();
      staleRunnable = new Runnable() {
        @Override
        public void run() {
          isStale = true;
          final Drawable drawable = ContextCompat.getDrawable(context, R.drawable.mapbox_user_icon);
          DrawableCompat.setTintMode(drawable, PorterDuff.Mode.SRC_IN);

          ValueAnimator colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), tintColor, staleStateTintColor);
          colorAnimator.setDuration(1000);
          colorAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
              DrawableCompat.setTint(drawable, (int) valueAnimator.getAnimatedValue());
              Bitmap icon = getBitmapFromDrawable(drawable);
              mapboxMap.addImage(USER_LOCATION_ICON, icon);
            }
          });
          colorAnimator.start();
        }

      };
      staleHandler.postDelayed(staleRunnable, STALE_USER_LOCATION_MS);
    }

//    void updateLatLng(double lat, double lon) {
//      if (latLng != null) {
//        latLng.setLatitude(lat);
//        latLng.setLongitude(lon);
//      }
//      // TODO animate the user location
//      MultiPoint multiPoint = MultiPoint.fromCoordinates(new double[][]{new double[]{lon, lat}});
//      FeatureCollection featureCollection = FeatureCollection.fromFeatures(new Feature[] {Feature.fromGeometry(multiPoint)});
//      GeoJsonSource source = mapboxMap.getSourceAs(USER_LOCATION_SOURCE);
//      if (source != null) {
//        source.setGeoJson(featureCollection);
//      } else {
//        userLocationSource = new GeoJsonSource(USER_LOCATION_SOURCE, featureCollection);
//        mapboxMap.addSource(userLocationSource);
//      }
//    }

    void updateAccuracy(final double lat, final double lon) {
      // TODO remove devision of 10 once https://github.com/mapbox/mapbox-java/issues/248 is resolved.
      // TODO filter out occasional flickering?
      // animate changes
//      if (accuracyAnimator != null) {
//        accuracyAnimator.end();
//        accuracyAnimator = null;
//      }

      // No need to animate since the accuracy is identical.
//      if (accuracy == location.getAccuracy()) {
//        return;
//      }

      accuracyAnimator = ValueAnimator.ofFloat(accuracy / 10, location.getAccuracy() / 10);
      accuracyAnimator.setDuration(750);
      accuracyAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator valueAnimator) {
          try {
            FeatureCollection featureCollection = createGeoJSONCircle(Position.fromCoordinates(
              lon,
              lat),
              (float) accuracyAnimator.getAnimatedValue()
            );
            GeoJsonSource source = mapboxMap.getSourceAs(USER_LOCATION_ACCURACY_SOURCE);
            if (source != null) {
              source.setGeoJson(featureCollection);
            } else {
              userLocationSource = new GeoJsonSource(USER_LOCATION_ACCURACY_SOURCE, featureCollection);
              mapboxMap.addSource(userLocationSource);
            }
          } catch (TurfException turfException) {
            turfException.printStackTrace();
          }
        }
      });
      accuracyAnimator.start();
      accuracy = location.getAccuracy();
    }
  }

  private class MyLocationTrackingBehavior extends MyLocation.MyLocationBehavior {

    @Override
    void updateLatLng(@NonNull Location location) {
      super.updateLatLng(location);
      if (latLng == null) {
        // first location fix
        latLng = new LatLng(location);
        locationUpdateTimestamp = SystemClock.elapsedRealtime();
      }

      // updateLatLng timestamp
      float previousUpdateTimeStamp = locationUpdateTimestamp;
      locationUpdateTimestamp = SystemClock.elapsedRealtime();

      // calculate animation duration
      float animationDuration;
      if (previousUpdateTimeStamp == 0) {
        animationDuration = 0;
      } else {
        animationDuration = (locationUpdateTimestamp - previousUpdateTimeStamp) * 1.1f
        /*make animation slightly longer*/;
     }

      // calculate interpolated location
      latLng = new LatLng(location);
      CameraPosition.Builder builder = new CameraPosition.Builder().target(latLng);

      // add direction
      if (myBearingTrackingMode == MyBearingTracking.GPS) {
        if (location.hasBearing()) {
          builder.bearing(location.getBearing());
        }
        setCompass(0, COMPASS_UPDATE_RATE_MS);
      }

      // accuracy
      updateAccuracy(location.getLatitude(), location.getLongitude());

      // ease to new camera position with a linear interpolator
      mapboxMap.easeCamera(CameraUpdateFactory.newCameraPosition(builder.build()), (int) animationDuration,
        false /*linear interpolator*/, false /*do not disable tracking*/, null);
    }
  }

  private class MyLocationShowBehavior extends MyLocation.MyLocationBehavior {

    @Override
    void updateLatLng(@NonNull final Location location) {
      super.updateLatLng(location);

      if (latLng == null) {
        // first location update
        latLng = new LatLng(location);
        locationUpdateTimestamp = SystemClock.elapsedRealtime();
      }

      // update LatLng location
      //LatLng newLocation = new LatLng(location);

      // update LatLng accuracy
      updateAccuracy(location.getLatitude(), location.getLongitude());

      // calculate updateLatLng time + add some extra offset to improve animation
      //long previousUpdateTimeStamp = locationUpdateTimestamp;
      //locationUpdateTimestamp = SystemClock.elapsedRealtime();
      //long locationUpdateDuration = (long) ((locationUpdateTimestamp - previousUpdateTimeStamp) * 1.2f);

      // animate changes
//      if (locationChangeAnimator != null) {
//        locationChangeAnimator.end();
//        locationChangeAnimator = null;
//      }

//      locationChangeAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
//      locationChangeAnimator.setDuration(locationUpdateDuration);
//      locationChangeAnimator.addUpdateListener(new MyLocation.MarkerCoordinateAnimatorListener(this,
//        latLng, newLocation
//      ));
//      locationChangeAnimator.start();
    }
  }
}
