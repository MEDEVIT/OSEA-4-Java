/*
 * Copyright (c) 2012 Patrick S. Hamilton (pat@eplimited.com), Wolfgang Halbeisen (halbeisen.wolfgang@gmail.com)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without restriction, 
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, 
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies 
 * or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, 
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE 
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package eplimited.osea.classification;

import eplimited.osea.classification.Classifier.ClassifyResult;
import eplimited.osea.detection.QRSDetector2;
import eplimited.osea.detection.QRSDetectorParameters;

/**
 * BDAC contains functions for handling Beat Detection And Classification.
 * The primary function calls a qrs detector.  When a beat is detected it waits
 * until a sufficient number of samples from the beat have occurred.  When the
 * beat is ready, BeatDetectAndClassify passes the beat and the timing
 * information on to the functions that actually classify the beat.
 */
public class BeatDetectionAndClassification 
	{

	private BDACParameters bdacParas ;
	private QRSDetectorParameters qrsDetParas ;
	private QRSDetector2 qrsDetector ;
	private NoiseChecker noiseChecker ;
	private Matcher matcher ;
	private Classifier classifier ;
	
	public final int ECG_BUFFER_LENGTH = 2000 ;	// Should be long enough for a beat
	// plus extra space to accommodate the maximum detection delay.

	public final int BEAT_QUE_LENGTH = 10 ; // Length of que for beats awaiting
	// classification.  Because of detection delays, Multiple beats
	// can occur before there is enough data to classify the first beat in the que.
	
	public int[] ECGBuffer = new int[ECG_BUFFER_LENGTH] ;
	public int ECGBufferIndex = 0 ;  // Circular data buffer.
	public int[] BeatBuffer ;
	public int[] BeatQue = new int[BEAT_QUE_LENGTH];
	public int BeatQueCount = 0 ;  // Buffer of detection delays.
	public int RRCount = 0 ;
	public int InitBeatFlag = 1 ;
	
	/**
	 * Create a new classifier with the given parameters.
	 * @param bdacParameters The sampleRate-dependent parameters
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 */
	public BeatDetectionAndClassification(BDACParameters bdacParameters, 
			QRSDetectorParameters qrsDetectorParameters) 
		{
		bdacParas = bdacParameters;
		qrsDetParas = qrsDetectorParameters ;
		BeatBuffer = new int[bdacParas.BEATLGTH] ;
		}
	
	/**
	 * Injects the objects
	 * 
	 * @param qrsDetector The qrsDetector
	 * @param noiseChecker The noiseChecker
	 * @param matcher The matcher
	 * @param classifier The classifier
	 */
	public void setObjects(QRSDetector2 qrsDetector, NoiseChecker noiseChecker, 
		Matcher matcher, Classifier classifier) 
		{
		this.qrsDetector = qrsDetector ;
		this.noiseChecker = noiseChecker ;
		this.matcher = matcher ;
		this.classifier = classifier ;
		}
	
	/**
	 * BeatDetectAndClassify() implements a beat detector and classifier.
	 * ECG samples are passed into BeatDetectAndClassify() one sample at a
	 * time.  BeatDetectAndClassify has been designed for a sample rate of
	 * 200 Hz.  When a beat has been detected and classified the detection
	 * delay is returned and the beat classification is returned. 
	 * For use in debugging, the number of the template
	 * that the beat was matched to is returned in via beatMatch.
	 * 
	 * @param ecgSample
	 * @return BeatDetectAndClassify() returns 0 if no new beat has been detected and
	 * classified.  If a beat has been classified, BeatDetectAndClassify returns
	 * the number of samples since the approximate location of the R-wave.
	 */
	public BeatDetectAndClassifyResult BeatDetectAndClassify(int ecgSample)
		{
		BeatDetectAndClassifyResult result = new BeatDetectAndClassifyResult();
		int detectDelay, rr = 0, i, j ;
		int noiseEst = 0, beatBegin = 0, beatEnd = 0 ;
		int domType ;
		int fidAdj ;
		int[] tempBeat = new int[(qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)*bdacParas.BEATLGTH] ;
	
		// Store new sample in the circular buffer.
	
		ECGBuffer[ECGBufferIndex] = ecgSample ;
		if(++ECGBufferIndex == ECG_BUFFER_LENGTH)
			ECGBufferIndex = 0 ;
	
		// Increment RRInterval count.
	
		++RRCount ;
	
		// Increment detection delays for any beats in the que.
	
		for(i = 0; i < BeatQueCount; ++i)
			++BeatQue[i] ;
	
		// Run the sample through the QRS detector.
	
		detectDelay = qrsDetector.QRSDet(ecgSample) ;
		if(detectDelay != 0)
			{
			BeatQue[BeatQueCount] = detectDelay ;
			++BeatQueCount ;
			}
	
		// Return if no beat is ready for classification.
	
		if((BeatQue[0] < (bdacParas.BEATLGTH-bdacParas.FIDMARK)*(qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE))
			|| (BeatQueCount == 0))
			{
			noiseChecker.NoiseCheck(ecgSample,0,rr, beatBegin, beatEnd) ;	// Update noise check buffer
			result.samplesSinceRWaveIfSuccess = 0;
			return result ;
			}
	
		// Otherwise classify the beat at the head of the que.
	
		rr = RRCount - BeatQue[0] ;	// Calculate the R-to-R interval
		detectDelay = RRCount = BeatQue[0] ;
	
		// Estimate low frequency noise in the beat.
		// Might want to move this into classify().
	
		domType = matcher.GetDominantType() ;
		if(domType == -1)
			{
			beatBegin = qrsDetParas.MS250 ;
			beatEnd = qrsDetParas.MS300 ;
			}
		else
			{
			beatBegin = (qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)*(bdacParas.FIDMARK-matcher.GetBeatBegin(domType)) ;
			beatEnd = (qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)*(matcher.GetBeatEnd(domType)-bdacParas.FIDMARK) ;
			}
		noiseEst = noiseChecker.NoiseCheck(ecgSample,detectDelay,rr,beatBegin,beatEnd) ;
	
		// Copy the beat from the circular buffer to the beat buffer
		// and reduce the sample rate by averageing pairs of data
		// points.
	
		j = ECGBufferIndex - detectDelay - (qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)*bdacParas.FIDMARK ;
		if(j < 0) j += ECG_BUFFER_LENGTH ;
	
		for(i = 0; i < (qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)*bdacParas.BEATLGTH; ++i)
			{
			tempBeat[i] = ECGBuffer[j] ;
			if(++j == ECG_BUFFER_LENGTH)
				j = 0 ;
			}
	
		DownSampleBeat(BeatBuffer,tempBeat) ;
	
		// Update the QUE.
	
		for(i = 0; i < BeatQueCount-1; ++i)
			BeatQue[i] = BeatQue[i+1] ;
		--BeatQueCount ;
	
	
		// Skip the first beat.
	
		if(InitBeatFlag != 0)
			{
			InitBeatFlag = 0 ;
			result.beatType = 13 ;
			result.beatMatch = 0 ;
			fidAdj = 0 ;
			}
	
		// Classify all other beats.
	
		else
			{
			ClassifyResult cr = classifier.Classify(BeatBuffer,rr,noiseEst);
			result.beatMatch = cr.beatMatch;
			result.beatType = cr.tempClass;
			fidAdj = cr.fidAdj;
			fidAdj *= qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE ;
	      }
	
		// Ignore detection if the classifier decides that this
		// was the trailing edge of a PVC.
	
		if(result.beatType == 100)
			{
			RRCount += rr ;
			result.samplesSinceRWaveIfSuccess = 0;
			return(result) ;
			}
	
		// Limit the fiducial mark adjustment in case of problems with
		// beat onset and offset estimation.
	
		if(fidAdj > qrsDetParas.MS80)
			fidAdj = qrsDetParas.MS80 ;
		else if(fidAdj < -qrsDetParas.MS80)
			fidAdj = -qrsDetParas.MS80 ;
	
		result.samplesSinceRWaveIfSuccess = detectDelay-fidAdj;
		return(result) ;
		}
	
	/**
	 * The result of a beat detection and classification
	 */
	public class BeatDetectAndClassifyResult 
		{
		/** If a beat was classified this field contains the samples since the R-Wave of the beat */
		public int samplesSinceRWaveIfSuccess;
		public int beatType;
		public int beatMatch;
		}
	
	private void DownSampleBeat(int[] beatOut, int[] beatIn)
		{
		int i ;
	
		for(i = 0; i < bdacParas.BEATLGTH; ++i)
			beatOut[i] = (beatIn[i<<1]+beatIn[(i<<1)+1])>>1 ;
		}
	}
