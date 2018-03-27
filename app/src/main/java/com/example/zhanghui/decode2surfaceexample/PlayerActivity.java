package com.example.zhanghui.decode2surfaceexample;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by thinkpad on 2018/3/14.
 */

public class PlayerActivity extends Activity implements SurfaceHolder.Callback{

    private static final String TAG = "PlayerActivity";
    private static final int STATE_IDLE = 1;
    private static final int STATE_PREPARING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_STOPPED = 4;
    private static final int STATE_RELEASED = 5;

    private static final int FORMAT_RESOLUTION_GOT = 100;

    private SurfaceView mSurfaceV;
    private Surface mSurface;
    private SurfaceHolder mSurfaceHolder;

    private static final MediaCodecList sMCL = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    private String decoder = null;
    MediaExtractor extractor;
    MediaCodec codec;
    private Integer mState;

    private final long kTimeOutUs = 5000; // 5ms timeout
    private boolean sawInputEOS = false;
    private boolean sawOutputEOS = false;
    private int deadDecoderCounter = 0;
    private int samplenum = 0;
    private int numframes = 0;
    private final Object mCompletionEvent = new Object();
    private boolean mCompleted;
    private String fileUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);

        mSurfaceV = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceV.getHolder().addCallback(this);

        Intent intent = getIntent();
        fileUrl = intent.getStringExtra("fileurl");

        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        this.sendBroadcast(i);

    }

    private Handler mainHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == FORMAT_RESOLUTION_GOT) {
                /*Get the width of the screen
                int screenWidth = getWindowManager().getDefaultDisplay().getWidth();
                int screenHeight = getWindowManager().getDefaultDisplay().getHeight();
                int rotationDegree = getWindowManager().getDefaultDisplay().getRotation();
                //Get the SurfaceView layout parameters
                android.view.ViewGroup.LayoutParams lp = mSurfaceV.getLayoutParams();
                if (rotationDegree == Surface.ROTATION_0 || rotationDegree == Surface.ROTATION_180) {
                    //Set the width of the SurfaceView to the width of the screen
                    lp.width = screenWidth;

                    //Set the height of the SurfaceView to match the aspect ratio of the video
                    //be sure to cast these as floats otherwise the calculation will likely be 0
                    lp.height = (int) (((float) msg.arg2 / (float) msg.arg1) * (float) screenWidth);
                } else {
                    lp.height = screenHeight;
                    lp.width = (int) (((float) msg.arg1 / (float) msg.arg2) * (float) screenHeight);
                }*/
                int width = mSurfaceV.getWidth();
                int height = mSurfaceV.getHeight();
                float boxWidth = width;
                float boxHeight = height;

                float videoWidth = msg.arg1;
                float videoHeight = msg.arg2;

                float wr = boxWidth / videoWidth;
                float hr = boxHeight / videoHeight;
                float ar = videoWidth / videoHeight;

                if (wr > hr)
                    width = (int) (boxHeight * ar);
                else
                    height = (int) (boxWidth / ar);

                Log.i(TAG, String.format("Scaled to %dx%d", width, height));

                android.view.ViewGroup.LayoutParams lp = mSurfaceV.getLayoutParams();
                lp.width = width;
                lp.height = height;
                //Commit the layout parameters
                mSurfaceV.setLayoutParams(lp);
                mSurfaceV.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        this.mSurfaceHolder = holder;
        mSurfaceHolder.setKeepScreenOn(true);
        new DecodeTask().execute(fileUrl);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (codec != null) {
            Log.e(TAG, "call surfaceDestroyed");
            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    Log.e(TAG, "call codec stop");
                    codec.stop();
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_STOPPED) {
                    Log.e(TAG, "call codec release");
                    codec.release();
                    extractor.release();
                } else if (mState == STATE_RELEASED) {
                    extractor.release();
                }
                mSurface = null;
                mState = STATE_IDLE;
                return;
            }
        }
    }

    public class DecodeTask extends AsyncTask<String, Integer, String> {

        @Override
        protected String doInBackground(String... url) {
            DecodeVideo(url[0]);
            return url[0];
        }

        @Override
        protected void onPostExecute(String s) {
        }
    }

    private void DecodeVideo(String fileUrl){
        mState = STATE_IDLE;
        extractor = new MediaExtractor();
        codec = null;
        synchronized (mState) {
            mState = STATE_PREPARING;
        }
        if (!canDecodeVideo(fileUrl, extractor)) {
            Log.i(TAG, "no supported decoder found ");
            return; //skip
        }
        int trackIndex = extractor.getSampleTrackIndex();
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        try {
            codec = MediaCodec.createByCodecName(decoder);
        } catch (IOException e) {
            Log.e(TAG, "failed to create decoder");
            return;
        }
        Log.i("@@@@", "using codec: " + codec.getName());
        codec.configure(format, mSurfaceHolder.getSurface(), null /* crypto */, 0 /* flags */);
        codec.start();
        synchronized (mState) {
            mState = STATE_PLAYING;
        }

        long decodeStartTime = SystemClock.elapsedRealtime();
        doSomeWork();
        long decodeEndTime = SystemClock.elapsedRealtime();

        synchronized (mState) {
            if (mState == STATE_PLAYING) {
                codec.stop();
                mState = STATE_STOPPED;
                codec.release();
                mState = STATE_RELEASED;
                extractor.release();
                mState = STATE_IDLE;
            }
        }
        return;
    }

    private void doSomeWork() {
        // start decode loop
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        final long kTimeOutUs = 5000; // 5ms timeout
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int deadDecoderCounter = 0;
        int samplenum = 0;
        int numframes = 0;
        while (!sawOutputEOS && deadDecoderCounter < 100 && mState == STATE_PLAYING) {
            // handle input
            Trace.beginSection("DecodeVideo handleinput");
            if (!sawInputEOS) {
                synchronized (mState) {
                    if (mState == STATE_PLAYING) {
                        int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);

                        if (inputBufIndex >= 0) {
                            ByteBuffer dstBuf = codec.getInputBuffer(inputBufIndex);

                            int sampleSize =
                                    extractor.readSampleData(dstBuf, 0 /* offset */);
                            long presentationTimeUs = extractor.getSampleTime();
                            Log.i("@@@@", "read sample " + samplenum + ":" + extractor.getSampleFlags()
                                    + " @ " + extractor.getSampleTime() + " size " + sampleSize);

                            if (sampleSize < 0) {
                                Log.d(TAG, "saw input EOS.");
                                sawInputEOS = true;
                                sampleSize = 0; // required otherwise queueInputBuffer returns invalid.
                            } else {
                                samplenum++; // increment before comparing with stopAtSample
                                if (samplenum == -1) {
                                    Log.d(TAG, "saw input EOS (stop at sample).");
                                    sawInputEOS = true; // tag this sample as EOS
                                }
                            }
                            codec.queueInputBuffer(
                                    inputBufIndex,
                                    0 /* offset */,
                                    sampleSize,
                                    presentationTimeUs,
                                    sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                            if (!sawInputEOS) {
                                extractor.advance();
                            }
                        }
                    }
                }
            }
            Trace.endSection();

            synchronized (mState) {
                if (mState == STATE_PLAYING) {
                    // handle output
                    int outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);

                    deadDecoderCounter++;
                    Trace.beginSection("DecodeVideo handleoutput");
                    if (outputBufIndex >= 0) {
                        if (info.size > 0) { // Disregard 0-sized buffers at the end.
                            deadDecoderCounter = 0;
                            numframes++;
                            Log.d(TAG, "got frame, size " + info.size + "/" + info.presentationTimeUs +
                                    "/" + numframes + "/" + info.flags);
                        }
                        codec.releaseOutputBuffer(outputBufIndex, true /* render */);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "saw output EOS.");
                            sawOutputEOS = true;
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            /*MediaFormat oformat = codec.getOutputFormat();
                            if (oformat.containsKey(MediaFormat.KEY_COLOR_FORMAT) &&
                                    oformat.containsKey(MediaFormat.KEY_WIDTH) &&
                                    oformat.containsKey(MediaFormat.KEY_HEIGHT)) {
                                int colorFormat = oformat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                                int width = oformat.getInteger(MediaFormat.KEY_WIDTH);
                                int height = oformat.getInteger(MediaFormat.KEY_HEIGHT);
                                Message msg = new Message();
                                msg.what = FORMAT_RESOLUTION_GOT;
                                msg.arg1 = width;
                                msg.arg2 = height;
                                mainHandler.sendMessage(msg);
                                Log.d(TAG, "output fmt: " + colorFormat + " dim " + width + "x" + height);
                            }*/
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "no output frame available yet");
                    }
                }
            }
            Trace.endSection();
        }
    }

    private void DecodeVideoAsync(String fileUrl, Surface surface){
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;

        if (!canDecodeVideo(fileUrl, extractor)) {
            Log.i(TAG, "no supported decoder found ");
            return; //skip
        }
        int trackIndex = extractor.getSampleTrackIndex();
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        try {
            codec = MediaCodec.createByCodecName(decoder);
        } catch (IOException e) {
            Log.e(TAG, "failed to create decoder");
            return;
        }
        Log.i("@@@@", "using codec: " + codec.getName());

        codec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (!sawInputEOS) {
                    if (index >= 0) {
                        ByteBuffer dstBuf = codec.getInputBuffer(index);

                        int sampleSize =
                                extractor.readSampleData(dstBuf, 0 /* offset */);
                        long presentationTimeUs = extractor.getSampleTime();
                        Log.i("@@@@", "read sample " + samplenum + ":" + extractor.getSampleFlags()
                                + " @ " + extractor.getSampleTime() + " size " + sampleSize);

                        if (sampleSize < 0) {
                            Log.d(TAG, "saw input EOS.");
                            sawInputEOS = true;
                            sampleSize = 0; // required otherwise queueInputBuffer returns invalid.
                        } else {
                            samplenum++; // increment before comparing with stopAtSample
                            if (samplenum == -1) {
                                Log.d(TAG, "saw input EOS (stop at sample).");
                                sawInputEOS = true; // tag this sample as EOS
                            }
                        }
                        codec.queueInputBuffer(
                                index,
                                0 /* offset */,
                                sampleSize,
                                presentationTimeUs,
                                sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                        if (!sawInputEOS) {
                            extractor.advance();
                        }
                    }
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                deadDecoderCounter++;
                if (index >= 0) {
                    if (info.size > 0) { // Disregard 0-sized buffers at the end.
                        deadDecoderCounter = 0;
                        numframes++;
                        Log.d(TAG, "got frame, size " + info.size + "/" + info.presentationTimeUs +
                                "/" + numframes + "/" + info.flags);
                    }
                    codec.releaseOutputBuffer(index, true /* render */);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "saw output EOS.");
                        sawOutputEOS = true;
                    }
                    if (sawOutputEOS || deadDecoderCounter > 100) {
                        signalCompletion();
                    }
                }  else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "no output frame available yet");
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {

            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                MediaFormat oformat = format;
                if (oformat.containsKey(MediaFormat.KEY_COLOR_FORMAT) &&
                        oformat.containsKey(MediaFormat.KEY_WIDTH) &&
                        oformat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    int colorFormat = oformat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
                    int width = oformat.getInteger(MediaFormat.KEY_WIDTH);
                    int height = oformat.getInteger(MediaFormat.KEY_HEIGHT);
                    Message msg = new Message();
                    msg.what = 2;
                    msg.arg1 = width;
                    msg.arg2 = height;
                    mainHandler.sendMessage(msg);
                    Log.d(TAG, "output fmt: " + colorFormat + " dim " + width + "x" + height);
                }
            }
        });
        codec.configure(format, surface, null /* crypto */, 0 /* flags */);
        codec.start();
        long decodeStartTime = SystemClock.elapsedRealtime();



        /*while (!sawOutputEOS && deadDecoderCounter < 100) {
            // handle input
            Trace.beginSection("DecodeVideo handleinput");

            Trace.endSection();


            Trace.beginSection("DecodeVideo handleoutput");

            Trace.endSection();
        }*/

        try {
            waitForCompletion(Long.MAX_VALUE);
        } catch (Exception e) {
            ;
        }

        long decodeEndTime = SystemClock.elapsedRealtime();
        codec.stop();
        codec.release();
        extractor.release();
        return;
    }

    private void waitForCompletion(long timeoutMs) throws Exception {
        synchronized (mCompletionEvent) {
            long timeoutExpiredMs = System.currentTimeMillis() + timeoutMs;

            while (!mCompleted) {
                mCompletionEvent.wait(timeoutExpiredMs - System.currentTimeMillis());
                if (System.currentTimeMillis() >= timeoutExpiredMs) {
                    throw new RuntimeException("decoding has timed out");
                }
            }
        }
    }

    private void signalCompletion() {
        synchronized (mCompletionEvent) {
            mCompleted = true;
            mCompletionEvent.notify();
        }
    }

    private boolean canDecodeVideo(String fileUrl, MediaExtractor ex) {
        try {
            ex.setDataSource(fileUrl);
            for (int i = 0; i < ex.getTrackCount(); ++i) {
                MediaFormat format = ex.getTrackFormat(i); //ex. call MPEG4Extractor.getTrackMetaData
                // only check for video codecs
                String mime = format.getString(MediaFormat.KEY_MIME).toLowerCase();
                if (!mime.startsWith("video/")) {
                    continue;
                }
                decoder = sMCL.findDecoderForFormat(format);
                if (decoder != null) {
                    ex.selectTrack(i);
                    return true;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "could not open path " + fileUrl);
        }
        return false;
    }
}
