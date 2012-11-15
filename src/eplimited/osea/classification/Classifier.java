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

import static eplimited.osea.classification.ECGCODES.NORMAL;
import static eplimited.osea.classification.ECGCODES.PVC;
import static eplimited.osea.classification.ECGCODES.UNKNOWN;
import eplimited.osea.classification.BeatAnalyzer.AnalyzeBeatResult;
import eplimited.osea.classification.Matcher.BestMorphMatchResult;
import eplimited.osea.detection.QRSDetectorParameters;

/**
 * Classify.cpp contains functions for classifying beats. 
 */
public class Classifier 
	{

	private BDACParameters        bdacParas;
	private QRSDetectorParameters qrsDetParas;
	private Matcher               matcher ;
	private RythmChecker          rythmChecker;
	private PostClassifier        postClassifier;
	private BeatAnalyzer          beatAnalyzer;
	
	// Detection Rule Parameters.
	private double MATCH_LIMIT              = 1.3 ; // Threshold for template matching without amplitude sensitivity.
	private double MATCH_WITH_AMP_LIMIT     = 2.5 ; // Threshold for matching index that is amplitude sensitive.
	private double PVC_MATCH_WITH_AMP_LIMIT = 0.9 ; // Amplitude sensitive limit for matching premature beats
	private int    BL_SHIFT_LIMIT           = 100 ; // Threshold for assuming a baseline shift.
	private int    NEW_TYPE_NOISE_THRESHOLD = 18 ;  // Above this noise level, do not create new beat types.
	private int    NEW_TYPE_HF_NOISE_LIMIT  = 75 ;  // Above this noise level, do not create new beat types.
	private double MATCH_NOISE_THRESHOLD    = 0.7 ; // Match threshold below which noise indications are ignored.

	// TempClass classification rule parameters.
	private double R2_DI_THRESHOLD     = 1.0 ; // Rule 2 dominant similarity index threshold
	private int    R3_WIDTH_THRESHOLD  ;       // Rule 3 width threshold.
	private double R7_DI_THRESHOLD     = 1.2 ; // Rule 7 dominant similarity index threshold
	private double R8_DI_THRESHOLD     = 1.5 ; // Rule 8 dominant similarity index threshold
	private double R9_DI_THRESHOLD     = 2.0 ; // Rule 9 dominant similarity index threshold
	private int    R10_BC_LIM          = 3 ;   // Rule 10 beat count limit.
	private double R10_DI_THRESHOLD    = 2.5 ; // Rule 10 dominant similarity index threshold
	private int    R11_MIN_WIDTH       ;       // Rule 11 minimum width threshold.
	private int    R11_WIDTH_BREAK     ;       // Rule 11 width break.
	private int    R11_WIDTH_DIFF1     ;       // Rule 11 width difference threshold 1
	private int    R11_WIDTH_DIFF2     ;       // Rule 11 width difference threshold 2
	private int    R11_HF_THRESHOLD    = 45 ;  // Rule 11 high frequency noise threshold.
	private int    R11_MA_THRESHOLD    = 14 ;  // Rule 11 motion artifact threshold.
	private int    R11_BC_LIM          = 1 ;   // Rule 11 beat count limit.
	private double R15_DI_THRESHOLD    = 3.5 ; // Rule 15 dominant similarity index threshold
	private int    R15_WIDTH_THRESHOLD ;       // Rule 15 width threshold.
	private int    R16_WIDTH_THRESHOLD ;       // Rule 16 width threshold.
	private int    R17_WIDTH_DELTA     ;       // Rule 17 difference threshold.
	private double R18_DI_THRESHOLD    = 1.5 ; // Rule 18 dominant similarity index threshold.
	private int    R19_HF_THRESHOLD    = 75 ;  // Rule 19 high frequency noise threshold.

	// Dominant monitor constants.
	private int DM_BUFFER_LENGTH = 180 ;
	private int IRREG_RR_LIMIT   = 60 ;
	
	private int[] RecentRRs   = new int[8] ;
	private int[] RecentTypes = new int[8] ;
	
	private int morphType       ;
	private int runCount        = 0 ;
	private int lastIsoLevel    = 0 ;
	private int lastRhythmClass = UNKNOWN ;
	private int lastBeatWasNew  = 0 ;
	private int AVELENGTH       ;
	private int brIndex         = 0 ;
	
	private int[] DMBeatTypes   = new int[DM_BUFFER_LENGTH] ;
	private int[] DMBeatClasses = new int[DM_BUFFER_LENGTH] ;
	private int[] DMBeatRhythms = new int[DM_BUFFER_LENGTH] ;
	private int[] DMNormCounts  = new int[8] ;
	private int[] DMBeatCounts  = new int[8] ;
	private int   DMIrregCount  = 0 ;

	/**
	 * Create a new classifier with the given parameters.
	 * @param bdacParameters The sampleRate-dependent parameters
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 * @param pc The PostClassifier
	 * @param ba The BeatAnalyzer
	 */
	public Classifier(BDACParameters bdacParameters, QRSDetectorParameters qrsDetectorParameters) 
		{
		bdacParas = bdacParameters ;
		qrsDetParas = qrsDetectorParameters ;
		
		R3_WIDTH_THRESHOLD  = bdacParameters.BEAT_MS90 ;
		R11_MIN_WIDTH       = bdacParameters.BEAT_MS110 ;
		R11_WIDTH_BREAK     = bdacParameters.BEAT_MS140 ; 
		R11_WIDTH_DIFF1     = bdacParameters.BEAT_MS40 ;
		R11_WIDTH_DIFF2     = bdacParameters.BEAT_MS60 ;
		R15_WIDTH_THRESHOLD = bdacParameters.BEAT_MS100 ;
		R16_WIDTH_THRESHOLD = bdacParameters.BEAT_MS100 ;
		R17_WIDTH_DELTA     = bdacParameters.BEAT_MS20 ;
		AVELENGTH           = bdacParameters.BEAT_MS50 ;
		for(int i = 0; i < DM_BUFFER_LENGTH; ++i)
			{
			DMBeatTypes[i] = -1 ;
			}
		}
	
	/**
	 * Injects the objects.
	 * 
	 * @param matcher The matcher
	 * @param rythmChecker The rythmChecker
	 * @param postClassifier The postClassifier
	 * @param beatAnalyzer The beatAnalyzer
	 */
	public void setObjects(Matcher matcher, RythmChecker rythmChecker, 
			PostClassifier postClassifier, BeatAnalyzer beatAnalyzer) 
		{
		this.matcher = matcher ;
		this.rythmChecker = rythmChecker ;
		this.postClassifier = postClassifier ;
		this.beatAnalyzer = beatAnalyzer ;
		}
	
	/**
	 * Classify() takes a beat buffer, the previous rr interval, and the present
	 * noise level estimate and returns a beat classification of NORMAL, PVC, or
	 * UNKNOWN. 
	 * 
	 * @param newBeat
	 * @param rr
	 * @param noiseLevel
	 * @return
	 */
	public ClassifyResult Classify(int[] newBeat,int rr, int noiseLevel)
		{
		ClassifyResult result = new ClassifyResult();
		int rhythmClass, beatClass, i, beatWidth, blShift ;
		double domIndex ;
		
		int domType, domWidth ;
		int tempClass ;
		int hfNoise ;

		hfNoise = HFNoiseCheck(newBeat) ;	// Check for muscle noise.
		rhythmClass = rythmChecker.RhythmChk(rr) ;			// Check the rhythm.

		// Estimate beat features.

		AnalyzeBeatResult ar = beatAnalyzer.AnalyzeBeat(newBeat) ;

		blShift = Math.abs(lastIsoLevel-ar.isoLevel) ;
		lastIsoLevel = ar.isoLevel ;

		// Make isoelectric level 0.

		for(i = 0; i < bdacParas.BEATLGTH; ++i)
			newBeat[i] -= ar.isoLevel ;

		// If there was a significant baseline shift since
		// the last beat and the last beat was a new type,
		// delete the new type because it might have resulted
		// from a baseline shift.

		if( (blShift > BL_SHIFT_LIMIT)
			&& (lastBeatWasNew == 1)
			&& (lastRhythmClass == NORMAL)
			&& (rhythmClass == NORMAL) )
			matcher.ClearLastNewType() ;

		lastBeatWasNew = 0 ;

		// Find the template that best matches this beat.

		BestMorphMatchResult bmr = matcher.BestMorphMatch(newBeat) ;
		morphType = bmr.matchType;

		// Disregard noise if the match is good. (New)

		if(bmr.matchIndex < MATCH_NOISE_THRESHOLD)
			hfNoise = noiseLevel = blShift = 0 ;

		// Apply a stricter match limit to premature beats.

		if((bmr.matchIndex < MATCH_LIMIT) && (rhythmClass == PVC) &&
				matcher.MinimumBeatVariation(morphType) && (bmr.mi2 > PVC_MATCH_WITH_AMP_LIMIT))
			{
			morphType = matcher.NewBeatType(newBeat) ;
			lastBeatWasNew = 1 ;
			}

		// Match if within standard match limits.

		else if((bmr.matchIndex < MATCH_LIMIT) && (bmr.mi2 <= MATCH_WITH_AMP_LIMIT))
			matcher.UpdateBeatType(morphType,newBeat,bmr.mi2,bmr.shiftAdj) ;

		// If the beat isn't noisy but doesn't match, start a new beat.

		else if((blShift < BL_SHIFT_LIMIT) && (noiseLevel < NEW_TYPE_NOISE_THRESHOLD)
			&& (hfNoise < NEW_TYPE_HF_NOISE_LIMIT))
			{
			morphType = matcher.NewBeatType(newBeat) ;
			lastBeatWasNew = 1 ;
			}

		// Even if it is a noisy, start new beat if it was an irregular beat.

		else if((lastRhythmClass != NORMAL) || (rhythmClass != NORMAL))
			{
			morphType = matcher.NewBeatType(newBeat) ;
			lastBeatWasNew = 1 ;
			}

		// If its noisy and regular, don't waste space starting a new beat.

		else morphType = bdacParas.MAXTYPES ;

		// Update recent rr and type arrays.

		for(i = 7; i > 0; --i)
			{
			RecentRRs[i] = RecentRRs[i-1] ;
			RecentTypes[i] = RecentTypes[i-1] ;
			}
		RecentRRs[0] = rr ;
		RecentTypes[0] = morphType ;

		lastRhythmClass = rhythmClass ;
		lastIsoLevel = ar.isoLevel ;

		// Fetch beat features needed for classification.
		// Get features from average beat if it matched.

		if(morphType != bdacParas.MAXTYPES)
			{
			beatClass = matcher.GetBeatClass(morphType) ;
			beatWidth = matcher.GetBeatWidth(morphType) ;
			result.fidAdj = matcher.GetBeatCenter(morphType)-bdacParas.FIDMARK ;

			// If the width seems large and there have only been a few
			// beats of this type, use the actual beat for width
			// estimate.

			if((beatWidth > ar.offset-ar.onset) && (matcher.GetBeatTypeCount(morphType) <= 4))
				{
				beatWidth = ar.offset-ar.onset ;
				result.fidAdj = ((ar.offset+ar.onset)/2)-bdacParas.FIDMARK ;
				}
			}

		// If this beat didn't match get beat features directly
		// from this beat.

		else
			{
			beatWidth = ar.offset-ar.onset ;
			beatClass = UNKNOWN ;
			result.fidAdj = ((ar.offset+ar.onset)/2)-bdacParas.FIDMARK ;
			}

		// Fetch dominant type beat features.

		domType = DomMonitor(morphType, rhythmClass, beatWidth, rr) ;
		domWidth = matcher.GetBeatWidth(domType) ;

		// Compare the beat type, or actual beat to the dominant beat.

		if((morphType != domType) && (morphType != 8))
			domIndex = matcher.DomCompare(morphType,domType) ;
		else if(morphType == 8)
			domIndex = matcher.DomCompare2(newBeat,domType) ;
		else domIndex = bmr.matchIndex ;

		// Update post classificaton of the previous beat.

		postClassifier.PostClassify(RecentTypes, domType, RecentRRs, beatWidth, domIndex, rhythmClass) ;

		// Classify regardless of how the morphology
		// was previously classified.

		tempClass = TempClass(rhythmClass, morphType, beatWidth, domWidth,
			domType, hfNoise, noiseLevel, blShift, domIndex) ;

		// If this morphology has not been classified yet, attempt to classify
		// it.

		if((beatClass == UNKNOWN) && (morphType < bdacParas.MAXTYPES))
			{

			// Classify as normal if there are 6 in a row
			// or at least two in a row that meet rhythm
			// rules for normal.

			runCount = GetRunCount() ;

			// Classify a morphology as NORMAL if it is not too wide, and there
			// are three in a row.  The width criterion prevents ventricular beats
			// from being classified as normal during VTACH (MIT/BIH 205).

			if((runCount >= 3) && (domType != -1) && (beatWidth < domWidth+bdacParas.BEAT_MS20))
				matcher.SetBeatClass(morphType,NORMAL) ;

			// If there is no dominant type established yet, classify any type
			// with six in a row as NORMAL.

			else if((runCount >= 6) && (domType == -1))
				matcher.SetBeatClass(morphType,NORMAL) ;

			// During bigeminy, classify the premature beats as ventricular if
			// they are not too narrow.

			else if(rythmChecker.IsBigeminy())
				{
				if((rhythmClass == PVC) && (beatWidth > bdacParas.BEAT_MS100))
					matcher.SetBeatClass(morphType,PVC) ;
				else if(rhythmClass == NORMAL)
					matcher.SetBeatClass(morphType,NORMAL) ;
				}
			}

		// Save morphology type of this beat for next classification.

		result.beatMatch = morphType ;

		beatClass = matcher.GetBeatClass(morphType) ;
	   
		// If the morphology has been previously classified.
		// use that classification.
	  //	return(rhythmClass) ;

		if(beatClass != UNKNOWN)
			{
			result.tempClass = beatClass ;
			return(result) ;
			}

		if(postClassifier.CheckPostClass(morphType) == PVC)
			{
			result.tempClass = PVC ;
			return(result) ;
			}

		// Otherwise use the temporary classification.
		result.tempClass = tempClass;
		return(result) ;
		}
	
	public class ClassifyResult {
		public int tempClass ;
		public int beatMatch ;
		public int fidAdj ;
	}

	/**
	 * HFNoiseCheck() gauges the high frequency (muscle noise) present in the
	 * beat template.  The high frequency noise level is estimated by highpass
	 * filtering the beat (y[n] = x[n] - 2*x[n-1] + x[n-2]), averaging the
	 * highpass filtered signal over five samples, and finding the maximum of
	 * this averaged highpass filtered signal.  The high frequency noise metric
	 * is then taken to be the ratio of the maximum averaged highpassed signal
	 * to the QRS amplitude.
	 * 
	 * @param beat
	 * @return
	 */
	private int HFNoiseCheck(int[] beat)
		{
		int maxNoiseAve = 0, i ;
		int sum=0;
		int[] aveBuff = new int[AVELENGTH] ;
		int avePtr = 0 ;
		int qrsMax = 0, qrsMin = 0 ;

		// Determine the QRS amplitude.

		for(i = bdacParas.FIDMARK-bdacParas.BEAT_MS70; i < bdacParas.FIDMARK+bdacParas.BEAT_MS80; ++i)
			if(beat[i] > qrsMax)
				qrsMax = beat[i] ;
			else if(beat[i] < qrsMin)
				qrsMin = beat[i] ;

		for(i = 0; i < AVELENGTH; ++i)
			aveBuff[i] = 0 ;

		for(i = bdacParas.FIDMARK-bdacParas.BEAT_MS280; i < bdacParas.FIDMARK+bdacParas.BEAT_MS280; ++i)
			{
			sum -= aveBuff[avePtr] ;
			aveBuff[avePtr] = Math.abs(beat[i] - (beat[i-bdacParas.BEAT_MS10]<<1) + beat[i-2*bdacParas.BEAT_MS10]) ;
			sum += aveBuff[avePtr] ;
			if(++avePtr == AVELENGTH)
				avePtr = 0 ;
			if((i < (bdacParas.FIDMARK - bdacParas.BEAT_MS50)) || (i > (bdacParas.FIDMARK + bdacParas.BEAT_MS110)))
				if(sum > maxNoiseAve)
					maxNoiseAve = sum ;
			}
		if((qrsMax - qrsMin)>=4)
			return((maxNoiseAve * (50/AVELENGTH))/((qrsMax-qrsMin)>>2)) ;
		else return(0) ;
		}

	/**
	 * TempClass() classifies beats based on their beat features, relative
	 * to the features of the dominant beat and the present noise level.
	 * 
	 * @param rhythmClass
	 * @param morphType
	 * @param beatWidth
	 * @param domWidth
	 * @param domType
	 * @param hfNoise
	 * @param noiseLevel
	 * @param blShift
	 * @param domIndex
	 * @return
	 */
	private int TempClass(int rhythmClass, int morphType,
		int beatWidth, int domWidth, int domType,
		int hfNoise, int noiseLevel, int blShift, double domIndex)
		{

		// Rule 1:  If no dominant type has been detected classify all
		// beats as UNKNOWN.

		if(domType < 0)
			return(UNKNOWN) ;

		// Rule 2:  If the dominant rhythm is normal, the dominant
		// beat type doesn't vary much, this beat is premature
		// and looks sufficiently different than the dominant beat
		// classify as PVC.

		if(matcher.MinimumBeatVariation(domType) && (rhythmClass == PVC)
			&& (domIndex > R2_DI_THRESHOLD) && (GetDomRhythm() == 1))
			return(PVC) ;

		// Rule 3:  If the beat is sufficiently narrow, classify as normal.

		if(beatWidth < R3_WIDTH_THRESHOLD)
			return(NORMAL) ;

		// Rule 5:  If the beat cannot be matched to any previously
		// detected morphology and it is not premature, consider it normal
		// (probably noisy).

		if((morphType == bdacParas.MAXTYPES) && (rhythmClass != PVC)) // == UNKNOWN
			return(NORMAL) ;

		// Rule 6:  If the maximum number of beat types have been stored,
		// this beat is not regular or premature and only one
		// beat of this morphology has been seen, call it normal (probably
		// noisy).

		if((matcher.GetTypesCount() == bdacParas.MAXTYPES) && (matcher.GetBeatTypeCount(morphType)==1)
				 && (rhythmClass == UNKNOWN))
			return(NORMAL) ;

		// Rule 7:  If this beat looks like the dominant beat and the
		// rhythm is regular, call it normal.

		if((domIndex < R7_DI_THRESHOLD) && (rhythmClass == NORMAL))
			return(NORMAL) ;

		// Rule 8:  If post classification rhythm is normal for this
		// type and its shape is close to the dominant shape, classify
		// as normal.

		if((domIndex < R8_DI_THRESHOLD) && (postClassifier.CheckPCRhythm(morphType) == NORMAL))
			return(NORMAL) ;

		// Rule 9:  If the beat is not premature, it looks similar to the dominant
		// beat type, and the dominant beat type is variable (noisy), classify as
		// normal.

		if((domIndex < R9_DI_THRESHOLD) && (rhythmClass != PVC) && matcher.WideBeatVariation(domType))
			return(NORMAL) ;

		// Rule 10:  If this beat is significantly different from the dominant beat
		// there have previously been matching beats, the post rhythm classification
		// of this type is PVC, and the dominant rhythm is regular, classify as PVC.

		if((domIndex > R10_DI_THRESHOLD)
			&& (matcher.GetBeatTypeCount(morphType) >= R10_BC_LIM) &&
			(postClassifier.CheckPCRhythm(morphType) == PVC) && (GetDomRhythm() == 1))
			return(PVC) ;

		// Rule 11: if the beat is wide, wider than the dominant beat, doesn't
		// appear to be noisy, and matches a previous type, classify it as
		// a PVC.

		if( (beatWidth >= R11_MIN_WIDTH) &&
			(((beatWidth - domWidth >= R11_WIDTH_DIFF1) && (domWidth < R11_WIDTH_BREAK)) ||
			(beatWidth - domWidth >= R11_WIDTH_DIFF2)) &&
			(hfNoise < R11_HF_THRESHOLD) && (noiseLevel < R11_MA_THRESHOLD) && (blShift < BL_SHIFT_LIMIT) &&
			(morphType < bdacParas.MAXTYPES) && (matcher.GetBeatTypeCount(morphType) > R11_BC_LIM))	// Rev 1.1

			return(PVC) ;

		// Rule 12:  If the dominant rhythm is regular and this beat is premature
		// then classify as PVC.

		if((rhythmClass == PVC) && (GetDomRhythm() == 1))
			return(PVC) ;

		// Rule 14:  If the beat is regular and the dominant rhythm is regular
		// call the beat normal.

		if((rhythmClass == NORMAL) && (GetDomRhythm() == 1))
			return(NORMAL) ;

		// By this point, we know that rhythm will not help us, so we
		// have to classify based on width and similarity to the dominant
		// beat type.

		// Rule 15: If the beat is wider than normal, wide on an
		// absolute scale, and significantly different from the
		// dominant beat, call it a PVC.

		if((beatWidth > domWidth) && (domIndex > R15_DI_THRESHOLD) &&
			(beatWidth >= R15_WIDTH_THRESHOLD))
			return(PVC) ;

		// Rule 16:  If the beat is sufficiently narrow, call it normal.

		if(beatWidth < R16_WIDTH_THRESHOLD)
			return(NORMAL) ;

		// Rule 17:  If the beat isn't much wider than the dominant beat
		// call it normal.

		if(beatWidth < domWidth + R17_WIDTH_DELTA)
			return(NORMAL) ;

		// If the beat is noisy but reasonably close to dominant,
		// call it normal.

		// Rule 18:  If the beat is similar to the dominant beat, call it normal.

		if(domIndex < R18_DI_THRESHOLD)
			return(NORMAL) ;

		// If it's noisy don't trust the width.

		// Rule 19:  If the beat is noisy, we can't trust our width estimate
		// and we have no useful rhythm information, so guess normal.

		if(hfNoise > R19_HF_THRESHOLD)
			return(NORMAL) ;

		// Rule 20:  By this point, we have no rhythm information, the beat
		// isn't particularly narrow, the beat isn't particulary similar to
		// the dominant beat, so guess a PVC.

		return(PVC) ;

		}

	/**
	 * DomMonitor, monitors which beat morphology is considered to be dominant.
	 * The dominant morphology is the beat morphology that has been most frequently
	 * classified as normal over the course of the last 120 beats.  The dominant
	 * beat rhythm is classified as regular if at least 3/4 of the dominant beats
	 * have been classified as regular.
	 * 
	 * @param morphType
	 * @param rhythmClass
	 * @param beatWidth
	 * @param rr
	 * @return
	 */
	public int DomMonitor(int morphType, int rhythmClass, int beatWidth, int rr)
		{
		
		int i, oldType, runCount, dom, max ;

		// Fetch the type of the beat before the last beat.

		i = brIndex - 2 ;
		if(i < 0)
			i += DM_BUFFER_LENGTH ;
		oldType = DMBeatTypes[i] ;

		// Once we have wrapped around, subtract old beat types from
		// the beat counts.

		if((DMBeatTypes[brIndex] != -1) && (DMBeatTypes[brIndex] != bdacParas.MAXTYPES))
			{
			--DMBeatCounts[DMBeatTypes[brIndex]] ;
			DMNormCounts[DMBeatTypes[brIndex]] -= DMBeatClasses[brIndex] ;
			if(DMBeatRhythms[brIndex] == UNKNOWN)
				--DMIrregCount ;
			}

		// If this is a morphology that has been detected before, decide
		// (for the purposes of selecting the dominant normal beattype)
		// whether it is normal or not and update the approporiate counts.

		if(morphType != 8)
			{

			// Update the buffers of previous beats and increment the
			// count for this beat type.

			DMBeatTypes[brIndex] = morphType ;
			++DMBeatCounts[morphType] ;
			DMBeatRhythms[brIndex] = rhythmClass ;

			// If the rhythm appears regular, update the regular rhythm
			// count.

			if(rhythmClass == UNKNOWN)
				++DMIrregCount ;

			// Check to see how many beats of this type have occurred in
			// a row (stop counting at six).

			i = brIndex - 1 ;
			if(i < 0) i += DM_BUFFER_LENGTH ;
			for(runCount = 0; (DMBeatTypes[i] == morphType) && (runCount < 6); ++runCount)
				if(--i < 0) i += DM_BUFFER_LENGTH ;

			// If the rhythm is regular, the beat width is less than 130 ms, and
			// there have been at least two in a row, consider the beat to be
			// normal.

			if((rhythmClass == NORMAL) && (beatWidth < bdacParas.BEAT_MS130) && (runCount >= 1))
				{
				DMBeatClasses[brIndex] = 1 ;
				++DMNormCounts[morphType] ;
				}

			// If the last beat was within the normal P-R interval for this beat,
			// and the one before that was this beat type, assume the last beat
			// was noise and this beat is normal.

			else if(rr < ((bdacParas.FIDMARK-matcher.GetBeatBegin(morphType))*qrsDetParas.SAMPLE_RATE/bdacParas.BEAT_SAMPLE_RATE)
				&& (oldType == morphType))
				{
				DMBeatClasses[brIndex] = 1 ;
				++DMNormCounts[morphType] ;
				}

			// Otherwise assume that this is not a normal beat.

			else DMBeatClasses[brIndex] = 0 ;
			}

		// If the beat does not match any of the beat types, store
		// an indication that the beat does not match.

		else
			{
			DMBeatClasses[brIndex] = 0 ;
			DMBeatTypes[brIndex] = -1 ;
			}

		// Increment the index to the beginning of the circular buffers.

		if(++brIndex == DM_BUFFER_LENGTH)
			brIndex = 0 ;

		// Determine which beat type has the most beats that seem
		// normal.

		dom = 0 ;
		for(i = 1; i < 8; ++i)
			if(DMNormCounts[i] > DMNormCounts[dom])
				dom = i ;

		max = 0 ;
		for(i = 1; i < 8; ++i)
			if(DMBeatCounts[i] > DMBeatCounts[max])
				max = i ;

		// If there are no normal looking beats, fall back on which beat
		// has occurred most frequently since classification began.

		if((DMNormCounts[dom] == 0) || (DMBeatCounts[max]/DMBeatCounts[dom] >= 2))			// == 0
			dom = matcher.GetDominantType() ;

		// If at least half of the most frequently occuring normal
		// type do not seem normal, fall back on choosing the most frequently
		// occurring type since classification began.

		else if(DMBeatCounts[dom]/DMNormCounts[dom] >= 2)
			dom = matcher.GetDominantType() ;

		// If there is any beat type that has been classfied as normal,
		// but at least 10 don't seem normal, reclassify it to UNKNOWN.

		for(i = 0; i < 8; ++i)
			if((DMBeatCounts[i] > 10) && (DMNormCounts[i] == 0) && (i != dom)
				&& (matcher.GetBeatClass(i) == NORMAL))
				matcher.SetBeatClass(i,UNKNOWN) ;

		// Save the dominant type in a global variable so that it is
		// accessable for debugging.

		return(dom) ;
		}

	private int GetDomRhythm()
		{
		if(DMIrregCount > IRREG_RR_LIMIT)
			return(0) ;
		else return(1) ;
		}


	public void AdjustDomData(int oldType, int newType)
		{
		int i ;

		for(i = 0; i < DM_BUFFER_LENGTH; ++i)
			{
			if(DMBeatTypes[i] == oldType)
				DMBeatTypes[i] = newType ;
			}

		if(newType != bdacParas.MAXTYPES)
			{
			DMNormCounts[newType] = DMNormCounts[oldType] ;
			DMBeatCounts[newType] = DMBeatCounts[oldType] ;
			}

		DMNormCounts[oldType] = DMBeatCounts[oldType] = 0 ;

		}

	public void CombineDomData(int oldType, int newType)
		{
		int i ;

		for(i = 0; i < DM_BUFFER_LENGTH; ++i)
			{
			if(DMBeatTypes[i] == oldType)
				DMBeatTypes[i] = newType ;
			}

		if(newType != bdacParas.MAXTYPES)
			{
			DMNormCounts[newType] += DMNormCounts[oldType] ;
			DMBeatCounts[newType] += DMBeatCounts[oldType] ;
			}

		DMNormCounts[oldType] = DMBeatCounts[oldType] = 0 ;

		}

	/**
	 * GetRunCount() checks how many of the present beat type have occurred in a row.
	 * 
	 * @return
	 */
	public int GetRunCount()
		{
		int i ;
		for(i = 1; (i < 8) && (RecentTypes[0] == RecentTypes[i]); ++i) ;
		return(i) ;
		}
	}
