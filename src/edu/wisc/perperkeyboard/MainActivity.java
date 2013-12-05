package edu.wisc.perperkeyboard;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;
import edu.wisc.jj.BasicKNN;
import edu.wisc.jj.SPUtil;


@SuppressLint("NewApi")
public class MainActivity extends Activity implements RecBufListener{
	/**********constant values****************/
	public static final String EXTRANAME = "edu.wisc.perperkeyboard.KNN";
	private static final String LTAG = "Kaichen Debug";
	public static final int STROKE_CHUNKSIZE = 2000;
	private static int TRAINNUM = 3; //how many keystroke we need to get for each key when training 
	public static BasicKNN mKNN;
	private enum InputStatus {
		AtoZ, //NUM, LEFT, RIGHT, BOTTOM
	}
	/****to track input stage*************/
	// expected chunk number in each stage
//	private final int[] ExpectedInputNum = { 26, 12, 4, 11, 5 };
//	private final int[] ExpectedInputNum = { 3, 1, 4, 11, 6 };
//	private final int[] ExpectedInputNum = {4,1};
	private final int[] ExpectedInputNum = {26};	
	private InputStatus inputstatus;
	private Set<InputStatus> elements;
	Iterator<InputStatus> it; 
	
	/*************UI********************/
	private static TextView text;
	private static Button mButton;
	private static EditText waveThre;
	private static EditText gyroThre;
	private Thread recordingThread;
	private RecBuffer mBuffer;
	private static TextView debugKNN;
	/************audio collection***********/
	private short[] strokeBuffer;
	private boolean inStrokeMiddle;
	private int strokeSamplesLeft;

	/***********training*****************/
	private ArrayList<ArrayList<String>> trainingItemName;
	private volatile int curTrainingItemIdx;
	//public int numToBeTrained;
	public boolean finishedTraining;
	private int TrainedNum = 0;  // for each key, how many key stroke we have collected
	
	/***********************gyro helper********************/
	public static GyroHelper mGyro;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		/**********init UI****************/
		setContentView(R.layout.activity_main);
		text = (TextView) findViewById(R.id.text_showhint);
		mButton = (Button) findViewById(R.id.mButton);
		debugKNN = (TextView) findViewById(R.id.text_debugKNN);
		waveThre = (EditText) findViewById(R.id.input_waveThreshold);
		gyroThre = (EditText) findViewById(R.id.input_gyroThreshold);
		waveThre.setText(String.valueOf(KeyStroke.THRESHOLD));
		
		waveThre.setOnEditorActionListener(new OnEditorActionListener() {
		    @Override
		    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		        boolean handled = false;
		        //halt = true;
		        if (actionId == EditorInfo.IME_ACTION_SEND) {
		        	String value = v.getText().toString();
		        	KeyStroke.THRESHOLD = Integer.parseInt(value);	        	
		        }
		        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		        imm.toggleSoftInput(0, 0);
		        return handled;
		    }
		});
		waveThre.clearFocus();
		gyroThre.setOnEditorActionListener(new OnEditorActionListener() {
		    @Override
		    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		        boolean handled = false;
		        //halt = true;
		        if (actionId == EditorInfo.IME_ACTION_SEND) {
		        	String value = v.getText().toString();
		        	mGyro.GYRO_DESK_THRESHOLD = Float.parseFloat(value);        	
		        }
		        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		        imm.toggleSoftInput(0, 0);
		        return handled;
		    }
		});
		gyroThre.clearFocus();
		/******init values*************/
		this.inStrokeMiddle = false;
		this.strokeSamplesLeft = 0;
		curTrainingItemIdx = 0;
		this.finishedTraining=false;		
		// iterator for input stage
		elements = EnumSet.allOf(InputStatus.class);
		it = elements.iterator();
		inputstatus = it.next();
		
		/*********create knn*************/
		mKNN = new BasicKNN();
		//mKNN.setTrainingSize(10);
		// add training item names
		trainingItemName = new ArrayList<ArrayList<String>>();
		addTrainingItem.addTrainingItems(trainingItemName);
		
		Log.d(LTAG,
				"training item names: "
						+ Arrays.toString(this.trainingItemName.toArray()));
		Log.d(LTAG, "main activity thread id : "
				+ Thread.currentThread().getId());
		
		//get gyro helper
		this.mGyro=new GyroHelper(getApplicationContext());
		gyroThre.setText(String.valueOf(mGyro.GYRO_DESK_THRESHOLD));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	
	@Override
	public void onStop(){
		Log.d(LTAG,"training activity stopped");
		if (this.recordingThread!=null){
			//make sure the recording thread is stopped
			recordingThread.interrupt();
			recordingThread=null;
		}
		super.onStop();
	}

	/**
	 * use this method to register on RecBuffer to let it know who is listening
	 * 
	 * @param r
	 */
	@Override
	public void register(RecBuffer r) {
		// set receiving thread to be this class
		r.setReceiver(this);
	}


	/**
	 * This method will be called every time the buffer of recording thread is
	 * full. It will do audio processing to get features. And also add these features to KNN
	 * 
	 * @param data
	 *            : audio data recorded. For stereo data, data at odd indexes
	 *            belongs to one channel, data at even indexes belong to another
	 * @throws FileNotFoundException
	 */
	@Override
	public void onRecBufFull(short[] data) {
//		Log.d(LTAG, "inside onRecBuf full");
		/*******************smooth the data******************/
		SPUtil.smooth(data);
		
		/*********************check whether gyro agrees that there is a key stroke *******************/
		long curTime=System.nanoTime();
		//first case: screen is being touched
		
		if (Math.abs(curTime-this.mGyro.lastTouchScreenTime) < mGyro.TOUCHSCREEN_TIME_INTERVAL){
			Log.d("onRecBufFull", "screen touch detected nearby");
			return;
		//2nd case: there is indeed some vibrations on the desk			
		} else if (Math.abs(curTime-this.mGyro.lastTouchDeskTime) >= mGyro.DESK_TIME_INTERVAL){ 
			Log.d("onRecBufFull", "no desk vibration feeled. not valid audio data. lastTouchDesktime: "+this.mGyro.lastTouchDeskTime + " .current time: "+curTime);
			return;
		}
		
		if (!this.inStrokeMiddle) { // if not in the middle of a stroke
			int startIdx = KeyStroke.detectStroke_threshold(data);
			if (-1 == startIdx) { // when there is no stroke
				return;
			} else { // there is an stroke
				// this whole stroke is inside current buffer
				if (data.length - startIdx >= STROKE_CHUNKSIZE * 2) {
					Log.d(LTAG,
							"key stroke, data length > chuncksize, data length: "
									+ data.length);
					this.inStrokeMiddle = false;
					this.strokeSamplesLeft = 0;
					this.strokeBuffer = Arrays.copyOfRange(data, startIdx,
							startIdx + STROKE_CHUNKSIZE * 2);
					this.runAudioProcessing();
				} else { // there are some samples left in the next buffer
					this.inStrokeMiddle = true;
					this.strokeSamplesLeft = STROKE_CHUNKSIZE * 2
							- (data.length - startIdx);
					this.strokeBuffer = new short[STROKE_CHUNKSIZE * 2];
					System.arraycopy(data, startIdx, strokeBuffer, 0,
							data.length - startIdx);
//					Log.d(LTAG,
//							"key stroke, data length < chuncksize, stroke start idx: "
//									+ startIdx + " stroke data length: "
//									+ String.valueOf(data.length - startIdx)
//									+ " stroke samples left "
//									+ this.strokeSamplesLeft);
				}
			}
		} else { // if in the middle of a stroke
			if (data.length >= strokeSamplesLeft) {
				System.arraycopy(data, 0, strokeBuffer, STROKE_CHUNKSIZE * 2
						- 1 - strokeSamplesLeft, strokeSamplesLeft);
				this.inStrokeMiddle = false;
				this.strokeSamplesLeft = 0;
				this.strokeBuffer= Arrays.copyOf(this.strokeBuffer,
						STROKE_CHUNKSIZE * 2);
				// get the audio features from this stroke and add it to the
				// training set, do it in background
				this.runAudioProcessing();				
//				Log.d(LTAG, "key stroke, data length >= samples left "
//						+ " stroke data length: " + String.valueOf(data.length)
//						+ " stroke samples left " + this.strokeSamplesLeft);
			} else { // if the length is smaller than the needed sample left
				System.arraycopy(data, 0, strokeBuffer, STROKE_CHUNKSIZE * 2
						- 1 - strokeSamplesLeft, data.length);
				this.inStrokeMiddle = true;
				this.strokeSamplesLeft = this.strokeSamplesLeft - data.length;
//				Log.d(LTAG,
//						"key stroke, data length < samples left size " + " stroke data length: "
//								+ String.valueOf(data.length)
//								+ " stroke samples left "
//								+ this.strokeSamplesLeft);
			}
		}
	}

	/***
	 * this function is called when button is click
	 * 
	 * @param view
	 */
	public void onClickButton(View view) {
		if(this.finishedTraining){
			//KNN knn = new KNN();
			Intent intent = new Intent(this, TestingActivity.class);
			//intent.putExtra("SampleObject", testKNN);
//			mKNN.test = 10;
		    startActivity(intent);
		} else {
			if (recordingThread == null) {
				text.setText(inputstatus.toString() + "is recording"+ "trainning:"+"\n" + trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx));
				Toast.makeText(getApplicationContext(),
						"Please Wait Until This disappear", Toast.LENGTH_SHORT)
						.show();
				Log.d(LTAG, "onClickButton starting another recording thread");
				// Init RecBuffer and thread
				mBuffer = new RecBuffer();
				recordingThread = new Thread(mBuffer);
				// register myself to RecBuffer to let it know I'm listening to him
				this.register(mBuffer);
				recordingThread.start();
			} else {
				//recordingThread.interrupt();
			//	recordingThread = null;
			}
		}
	}
	
	public void onClickClear(View view){
			     //remove latest input
	     if(curTrainingItemIdx == 0 && TrainedNum == 0){ //means we just got to new input stage
	       //find previous stage
	       Set<InputStatus> tempelements =  EnumSet.allOf(InputStatus.class);;
	       Iterator<InputStatus> newit = elements.iterator();
	       Iterator<InputStatus> previousIt = null;
	       while(newit.hasNext()){
	         if (newit.hashCode() == it.hashCode() && previousIt != null)
	         {
	           it = previousIt;
	           curTrainingItemIdx = ExpectedInputNum[inputstatus.ordinal()]-1;
	           TrainedNum = TRAINNUM-1;
	           mKNN.removeLatestInput();
	           break;
	         }
	         newit.next();
	       }
	    }else if(TrainedNum == 0){ // means we just got to new input key, but not the first key of input stage
	      curTrainingItemIdx --;
	       TrainedNum = TRAINNUM -1;
	      mKNN.removeLatestInput();
	     }else{
	       TrainedNum --;
	       mKNN.removeLatestInput();
	     }
	     //show new info
	     text.setText(inputstatus.toString()  + "is recording. " + "\n"
	         + "current training: "
	         + trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx)+"\n"
	         + String.valueOf(TRAINNUM - TrainedNum) + "left");
	     debugKNN.setText(mKNN.getChars());	
	
	    /*	     
		/////////////////Each Key Several Times////////////////////
		//remove latest input
		if(curTrainingItemIdx == 0){ //means we just got to new input stage
			//find previous stage
			Set<InputStatus> elements =  EnumSet.allOf(InputStatus.class);
			Iterator<InputStatus> firstEle = elements.iterator();
			InputStatus fistStatus = firstEle.next();
			if(inputstatus.equals(fistStatus)){
				if(TrainedNum!=0){
					Iterator<InputStatus> newit = elements.iterator();
					//TODO this code might have bug
					while(newit.hasNext()){
						inputstatus = newit.next();
					}
					it = newit;
					int len = ExpectedInputNum.length -1;
					curTrainingItemIdx = ExpectedInputNum[len] -1;
					TrainedNum --;
					mKNN.removeLatestInput();
				}
			}else{
				Iterator<InputStatus> newit = elements.iterator();
				InputStatus previousstatus = newit.next();
				//find previous stage
				while(newit.hasNext() && previousstatus.ordinal() < inputstatus.ordinal()-1)
				{
					previousstatus = newit.next();
				}
				it = newit;
				inputstatus = previousstatus;
				curTrainingItemIdx = ExpectedInputNum[inputstatus.ordinal()] -1;
				mKNN.removeLatestInput();
			}
			
			
		}else{ // means we just got to new input key, but not the first key of input stage
			curTrainingItemIdx --;
			mKNN.removeLatestInput();
		}
		//show new info
		text.setText(inputstatus.toString()  + "is recording. " + "\n"
				+ "current training: "
				+ trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx)+"\n"
				+ String.valueOf(TRAINNUM - TrainedNum) + "left");
		debugKNN.setText(mKNN.getChars());
	*/
	}
	
	public void onClickRestart(View view){
		//clear KNN
		mKNN.clear();
		//clear iterator and inputstatus
		elements = EnumSet.allOf(InputStatus.class);
		it = elements.iterator();
		inputstatus= it.next();
		//clear trained number
		TrainedNum = 0;
		curTrainingItemIdx = 0;
		//show new info
		text.setText(inputstatus.toString()  + "is recording. " + "\n"
				+ "current training: "
				+ trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx)+"\n"
				+ String.valueOf(TRAINNUM - TrainedNum) + "left");
		debugKNN.setText(mKNN.getChars());
		if(this.finishedTraining){
			Toast.makeText(getApplicationContext(),
					"Please Wait Until This disappear", Toast.LENGTH_SHORT)
					.show();
			recordingThread.start();
			this.finishedTraining = false;
		}
	}

	/**
	 * audio processing. extract features from audio. Add features to KNN.
	 */
	public void runAudioProcessing() {
		// get the audio features from this stroke and add it
		// to the training set, do it in background
		short[] audioStroke = this.strokeBuffer;
		// separate left and right channel
		short[][] audioStrokeData = KeyStroke.seperateChannels(audioStroke);
		this.strokeBuffer=null;
		// get features
		double[] features = SPUtil.getAudioFeatures(audioStrokeData);
		Log.d(LTAG, " adding features for item " + trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx));
		mKNN.addTrainingItem(
				trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx),
				features);
		
		//training order each key several times
		TrainedNum ++;
        if(TrainedNum == TRAINNUM){
                this.curTrainingItemIdx++;        
                TrainedNum = 0;
                if (this.ExpectedInputNum[inputstatus.ordinal()] == this.curTrainingItemIdx){
                        if(it.hasNext())
                        {
                                inputstatus = it.next();
                                Log.d(LTAG, "change to next character. next char: "+inputstatus);                                
                                //curTrainingItemIdx  = 0;
                                TrainedNum = 0;
        
                        }
                        else{
                                        this.finishedTraining=true;
                                        //kill recording thread (myself)
                                        recordingThread.interrupt();
                        }
                }
        } 
		//training order alphabetical
		/*
		curTrainingItemIdx++;		
		if (this.ExpectedInputNum[inputstatus.ordinal()] == this.curTrainingItemIdx){
			if(it.hasNext())
			{
				inputstatus = it.next();
				Log.d(LTAG, "change to next character. next char: "+inputstatus);				
				curTrainingItemIdx  = 0;
			}
			else{
				TrainedNum ++;
				if(TrainedNum == TRAINNUM)
				{
					this.finishedTraining=true;
					//kill recording thread (myself)
					recordingThread.interrupt();
				}else{
					elements = EnumSet.allOf(InputStatus.class);
					it = elements.iterator();
					inputstatus = it.next();
					curTrainingItemIdx = 0;
				}
			}
		} */
		//update UI
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				if(!finishedTraining)
				{
					text.setText(inputstatus.toString()  + "is recording. " + "\n"
				+ "current training: "
				+ trainingItemName.get(inputstatus.ordinal()).get(curTrainingItemIdx)+"\n"
				+ String.valueOf(TRAINNUM - TrainedNum) + "left");
					debugKNN.setText(mKNN.getChars());	
				}
				else {
					text.setText("Training finished, click to start testing");
					debugKNN.setText(mKNN.getChars());
					mButton.setText("Click to Test");
				}
			}
		});
	}

	
	/***************************start and stop gyro helper ****************************/
	
	@Override
	  protected void onResume() {
	    super.onResume();
	    if (mGyro != null){
	    	mGyro.register();
	    } else {
	    	Log.d(LTAG, "try to register gyro sensor. but there is no GyroHelper class used");
	    }
	  }

	  @Override
	  protected void onPause() {
	    super.onPause();
	    if (mGyro != null){
	    	mGyro.unregister();
	    } else {
	    	Log.d(LTAG, "try to register gyro sensor. but there is no GyroHelper class used");
	    }
	  }	

}

/**
 * A class to add trainning item to the ArrayList
 * Contains only on function
 * @author kevin
 */
class addTrainingItem {
	public static void addTrainingItems(ArrayList<ArrayList<String>> trainingItemName)
	{
		ArrayList<String> AtoZArray = new ArrayList<String>();
		//TODO This is only used for debug
//		AtoZArray.add("LShift");
//		AtoZArray.add("Caps");
		// add characters into training item
		for (int idx = 0; idx < 26; idx++)
			AtoZArray.add(String.valueOf((char)('a' + idx)));
		trainingItemName.add(AtoZArray);
		ArrayList<String> NumArray = new ArrayList<String>();
		// add numbers into training item
		for(int idx  = 0; idx < 9; idx++)
			NumArray.add(String.valueOf((char)('1'+idx)));
		NumArray.add(String.valueOf('0'));
		NumArray.add(String.valueOf('-'));
		NumArray.add(String.valueOf('='));
		trainingItemName.add(NumArray);
		
		ArrayList<String> LeftArray = new ArrayList<String>();
		// add left into training item
		LeftArray.add("`");
		LeftArray.add("Tab");
		LeftArray.add("Caps");
		LeftArray.add("LShift");
		trainingItemName.add(LeftArray);
		
		
		ArrayList<String> RightArray = new ArrayList<String>();
		// add right into training item
		RightArray.add("BackSpace");
		RightArray.add("\\");
		RightArray.add("]");
		RightArray.add("[");
		RightArray.add("Enter");
		RightArray.add("'");
		RightArray.add(";");
		RightArray.add("RShift");
		RightArray.add("/");
		RightArray.add(".");
		RightArray.add(",");
		trainingItemName.add(RightArray);
		
		ArrayList<String> BottomArray = new ArrayList<String>();
		// add numbers into training item
		BottomArray.add("L Ctrl");
		//BottomArray.add("Windows");
		BottomArray.add("L Alt");
		BottomArray.add("Space");
		BottomArray.add("R Alt");
		BottomArray.add("R Ctrl");
		trainingItemName.add(BottomArray);		
	}
	
}
