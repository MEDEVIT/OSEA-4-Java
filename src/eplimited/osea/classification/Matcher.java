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
import eplimited.osea.detection.QRSDetectorParameters;

/**
 * Match beats to previous beats.
 * 
 * Matcher contains functions for managing template matching of beats and
 * managing of feature data associated with each beat type.  These
 * functions are called functions in Classifier.  Beats are matched to
 * previoiusly detected beats types based on how well they match point by point
 * in a MATCH_LENGTH region centered on FIDMARK (R-wave location).
 */
public class Matcher 
	{

	private BDACParameters bdacParas ;
	private PostClassifier postClassifier ;
	private BeatAnalyzer   beatAnalyzer ;
	private Classifier     classifier ;
	
	private int    MATCH_LENGTH   ;       // Number of points used for beat matching.
	private double MATCH_LIMIT    = 1.2 ; // Match limit used testing whether two beat types might be combined.
	private double COMBINE_LIMIT  = 0.8 ; // Limit used for deciding whether two types can be combined.
	private double WIDE_VAR_LIMIT = 0.50 ;
	
	private int MATCH_START ;     // Starting point for beat matching
	private int MATCH_END   ;     // End point for beat matching.
	private int MAXPREV     = 8 ; // Number of preceeding beats used as beat features.
	private int MAX_SHIFT   ;
	
	private int[][]    BeatTemplates ;
	private int[]      BeatCounts ;
	private int[]      BeatWidths ;
	private int[]      BeatClassifications ;
	private int[]      BeatBegins ;
	private int[]      BeatEnds ;
	private int[]      BeatsSinceLastMatch ;
	private int[]      BeatAmps ;
	private int[]      BeatCenters ;
	private double[][] MIs ;

	private int TypeCount = 0 ;
	
	/**
	 * Create a new Matcher with the given parameters.
	 * @param bdacParameters The sampleRate-dependent parameters
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 */
	public Matcher(BDACParameters bdacParameters, QRSDetectorParameters qrsDetectorParameters) 
		{
		bdacParas = bdacParameters ;
		MATCH_LENGTH        = bdacParas.BEAT_MS300 ;
		MATCH_START         = (bdacParas.FIDMARK-(MATCH_LENGTH/2)) ;
		MATCH_END           = (bdacParas.FIDMARK+(MATCH_LENGTH/2)) ;
		MAX_SHIFT           = bdacParas.BEAT_MS40 ;
		BeatTemplates       = new int[bdacParas.MAXTYPES][bdacParas.BEATLGTH] ;
		BeatCounts          = new int[bdacParas.MAXTYPES] ;
		BeatWidths          = new int[bdacParas.MAXTYPES] ;
		BeatClassifications = new int[bdacParas.MAXTYPES] ;		
		BeatBegins          = new int[bdacParas.MAXTYPES] ;
		BeatEnds            = new int[bdacParas.MAXTYPES] ;
		BeatsSinceLastMatch = new int[bdacParas.MAXTYPES] ;
		BeatAmps            = new int[bdacParas.MAXTYPES] ;
		BeatCenters         = new int[bdacParas.MAXTYPES] ;
		MIs                 = new double [bdacParas.MAXTYPES][8] ;
		for(int i = 0; i < bdacParameters.MAXTYPES; ++i)
			{
			BeatClassifications[i] = UNKNOWN ;
			}
		}
	
	/**
	 * Injects the objects.
	 * 
	 * @param postClassifier The postClassifier
	 * @param beatAnalyzer The beatAnalyzer
	 * @param classifier The classifier
	 */
	public void setObjects(PostClassifier postClassifier, BeatAnalyzer beatAnalyzer, Classifier classifier)
		{
		this.postClassifier = postClassifier ;
		this.beatAnalyzer   = beatAnalyzer ;
		this.classifier     = classifier ;
		}
	
	/**
	 * CompareBeats() takes two beat buffers and compares how well they match
	 * point-by-point.  Beat2 is shifted and scaled to produce the closest
	 * possible match.  The metric returned is the sum of the absolute
	 * differences between beats divided by the amplitude of the beats. 
	 * The shift used for the match is returned via the pointer *shiftAdj.
	 * 
	 * @param beat1
	 * @param beat2
	 * @return
	 */
	private CompareBeatsResult CompareBeats(int[] beat1, int[] beat2)
		{
		CompareBeatsResult result = new CompareBeatsResult();
		int i, max, min, magSum, shift ;
		long beatDiff, meanDiff, minDiff = 0, minShift = 0 ;
		double scaleFactor, tempD ;

		// Calculate the magnitude of each beat.

		max = min = beat1[MATCH_START] ;
		for(i = MATCH_START+1; i < MATCH_END; ++i)
			if(beat1[i] > max)
				max = beat1[i] ;
			else if(beat1[i] < min)
				min = beat1[i] ;

		magSum = max - min ;

		i = MATCH_START ;
		max = min = beat2[i] ;
		for(i = MATCH_START+1; i < MATCH_END; ++i)
			if(beat2[i] > max)
				max = beat2[i] ;
			else if(beat2[i] < min)
				min = beat2[i] ;

		// magSum += max - min ;
		scaleFactor = magSum ;
		scaleFactor /= max-min ;
		magSum *= 2 ;

		// Calculate the sum of the point-by-point
		// absolute differences for five possible shifts.

		for(shift = -MAX_SHIFT; shift <= MAX_SHIFT; ++shift)
			{
			for(i = bdacParas.FIDMARK-(MATCH_LENGTH>>1), meanDiff = 0;
				i < bdacParas.FIDMARK + (MATCH_LENGTH>>1); ++i)
				{
				tempD = beat2[i+shift] ;
				tempD *= scaleFactor ;
				meanDiff += beat1[i]- tempD ; // beat2[i+shift] ;
				}
			meanDiff /= MATCH_LENGTH ;

			for(i = bdacParas.FIDMARK-(MATCH_LENGTH>>1), beatDiff = 0;
				i < bdacParas.FIDMARK + (MATCH_LENGTH>>1); ++i)
				{
				tempD = beat2[i+shift] ;
				tempD *= scaleFactor ;
				beatDiff += Math.abs(beat1[i] - meanDiff- tempD) ; // beat2[i+shift]  ) ;
				}


			if(shift == -MAX_SHIFT)
				{
				minDiff = beatDiff ;
				minShift = -MAX_SHIFT ;
				}
			else if(beatDiff < minDiff)
				{
				minDiff = beatDiff ;
				minShift = shift ;
				}
			}

		result.metric = minDiff ;
		result.shiftAdj = (int) minShift ;
		result.metric /= magSum ;

		// Metric scales inversely with match length.
		// algorithm was originally tuned with a match
		// length of 30.

		result.metric *= 30 ;
		result.metric /= MATCH_LENGTH ;
		return(result) ;
		}

	/**
	 * CompareBeats2 is nearly the same as CompareBeats above, but beat2 is
	 * not scaled before calculating the match metric.  The match metric is
	 * then the sum of the absolute differences divided by the average amplitude
	 * of the two beats.
	 * 
	 * @param beat1
	 * @param beat2
	 * @return
	 */
	private CompareBeatsResult CompareBeats2(int[] beat1, int[] beat2)
		{
		CompareBeatsResult result = new CompareBeatsResult();
		int i, max, min, shift ;
		int mag1, mag2 ;
		long beatDiff, meanDiff, minDiff = 0, minShift = 0 ;

		// Calculate the magnitude of each beat.

		max = min = beat1[MATCH_START] ;
		for(i = MATCH_START+1; i < MATCH_END; ++i)
			if(beat1[i] > max)
				max = beat1[i] ;
			else if(beat1[i] < min)
				min = beat1[i] ;

		mag1 = max - min ;

		i = MATCH_START ;
		max = min = beat2[i] ;
		for(i = MATCH_START+1; i < MATCH_END; ++i)
			if(beat2[i] > max)
				max = beat2[i] ;
			else if(beat2[i] < min)
				min = beat2[i] ;

		mag2 = max-min ;

		// Calculate the sum of the point-by-point
		// absolute differences for five possible shifts.

		for(shift = -MAX_SHIFT; shift <= MAX_SHIFT; ++shift)
			{
			for(i = bdacParas.FIDMARK-(MATCH_LENGTH>>1), meanDiff = 0;
				i < bdacParas.FIDMARK + (MATCH_LENGTH>>1); ++i)
				meanDiff += beat1[i]- beat2[i+shift] ;
			meanDiff /= MATCH_LENGTH ;

			for(i = bdacParas.FIDMARK-(MATCH_LENGTH>>1), beatDiff = 0;
				i < bdacParas.FIDMARK + (MATCH_LENGTH>>1); ++i)
				beatDiff += Math.abs(beat1[i] - meanDiff- beat2[i+shift]) ; ;

			if(shift == -MAX_SHIFT)
				{
				minDiff = beatDiff ;
				minShift = -MAX_SHIFT ;
				}
			else if(beatDiff < minDiff)
				{
				minDiff = beatDiff ;
				minShift = shift ;
				}
			}

		result.metric = minDiff ;
		result.shiftAdj = (int) minShift ;
		result.metric /= (mag1+mag2) ;

		// Metric scales inversely with match length.
		// algorithm was originally tuned with a match
		// length of 30.

		result.metric *= 30 ;
		result.metric /= MATCH_LENGTH ;

		return(result) ;
		}
	
	private class CompareBeatsResult {
		public double metric;
		public int shiftAdj;
	}

	/**
	 * UpdateBeat() averages a new beat into an average beat template by adding
	 * 1/8th of the new beat to 7/8ths of the average beat.
	 * 
	 * @param aveBeat
	 * @param newBeat
	 * @param shift
	 */
	private void UpdateBeat(int[] aveBeat, int[] newBeat, int shift)
		{
		int i ;
		long tempLong ;

		for(i = 0; i < bdacParas.BEATLGTH; ++i)
			{
			if((i+shift >= 0) && (i+shift < bdacParas.BEATLGTH))
				{
				tempLong = aveBeat[i] ;
				tempLong *= 7 ;
				tempLong += newBeat[i+shift] ;
				tempLong >>= 3 ;
				aveBeat[i] = (int) tempLong ;
				}
			}
		}

	/**
	 * GetTypesCount returns the number of types that have
	 * been detected.
	 * 
	 * @return
	 */
	public int GetTypesCount()
		{
		return(TypeCount) ;
		}

	/**
	 * GetBeatTypeCount returns the number of beats of a
	 * a particular type have been detected.
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatTypeCount(int type)
		{
		return(BeatCounts[type]) ;
		}

	/**
	 * GetBeatWidth returns the QRS width estimate for
	 * a given type of beat.
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatWidth(int type)
		{
		return(BeatWidths[Math.max(type, 0)]) ;
		}

	/**
	 * GetBeatCenter returns the point between the onset and
	 * offset of a beat.
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatCenter(int type)
		{
		return(BeatCenters[type]) ;
		}

	/**
	 * GetBeatClass returns the present classification for
	 * a given beat type (NORMAL, PVC, or UNKNOWN).
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatClass(int type)
		{
		if(type == bdacParas.MAXTYPES)
			return(UNKNOWN) ;
		return(BeatClassifications[type]) ;
		}

	/**
	 * SetBeatClass sets up a beat classifation for a given type.
	 * 
	 * @param type
	 * @param beatClass
	 */
	public void SetBeatClass(int type, int beatClass)
		{
		BeatClassifications[type] = beatClass ;
		}

	/**
	 * NewBeatType starts a new beat type by storing the new beat and its
	 * features as the next available beat type.
	 * 
	 * @param newBeat
	 * @return
	 */
	public int NewBeatType(int[] newBeat )
		{
		int i;
		int mcType;

		// Update count of beats since each template was matched.

		for(i = 0; i < TypeCount; ++i)
			++BeatsSinceLastMatch[i] ;

		if(TypeCount < bdacParas.MAXTYPES)
			{
			for(i = 0; i < bdacParas.BEATLGTH; ++i)
				BeatTemplates[TypeCount][i] = newBeat[i] ;

			BeatCounts[TypeCount] = 1 ;
			BeatClassifications[TypeCount] = UNKNOWN ;
			AnalyzeBeatResult abr = beatAnalyzer.AnalyzeBeat(BeatTemplates[TypeCount]) ;
			
			
			BeatWidths[TypeCount] = abr.offset-abr.onset ;
			BeatCenters[TypeCount] = (abr.offset+abr.onset)/2 ;
			BeatBegins[TypeCount] = abr.beatBegin ;
			BeatEnds[TypeCount] = abr.beatEnd ;
			BeatAmps[TypeCount] = abr.amp ;

			BeatsSinceLastMatch[TypeCount] = 0 ;

			++TypeCount ;
			return(TypeCount-1) ;
			}

		// If we have used all the template space, replace the beat
		// that has occurred the fewest number of times.

		else
			{
			// Find the template with the fewest occurances,
			// that hasn't been matched in at least 500 beats.

			mcType = -1 ;

			if(mcType == -1)
				{
				mcType = 0 ;
				for(i = 1; i < bdacParas.MAXTYPES; ++i)
					if(BeatCounts[i] < BeatCounts[mcType])
						mcType = i ;
					else if(BeatCounts[i] == BeatCounts[mcType])
						{
						if(BeatsSinceLastMatch[i] > BeatsSinceLastMatch[mcType])
							mcType = i ;
						}
				}

			// Adjust dominant beat monitor data.

			classifier.AdjustDomData(mcType, bdacParas.MAXTYPES) ;

			// Substitute this beat.

			for(i = 0; i < bdacParas.BEATLGTH; ++i)
				BeatTemplates[mcType][i] = newBeat[i] ;

			BeatCounts[mcType] = 1 ;
			BeatClassifications[mcType] = UNKNOWN ;
			AnalyzeBeatResult abr = beatAnalyzer.AnalyzeBeat(BeatTemplates[mcType]) ;
			BeatWidths[mcType] = abr.offset-abr.onset ;
			BeatCenters[mcType] = (abr.offset+abr.onset)/2 ;
			BeatBegins[mcType] = abr.beatBegin ;
			BeatEnds[mcType] = abr.beatEnd ;
			BeatsSinceLastMatch[mcType] = 0 ;
	    	BeatAmps[mcType] = abr.amp ;
			return(mcType) ;
			}
		}

	/**
	 * BestMorphMatch tests a new beat against all available beat types and
	 * returns (via pointers) the existing type that best matches, the match
	 * metric for that type, and the shift used for that match.
	 * 
	 * @param newBeat
	 * @return
	 */
	public BestMorphMatchResult BestMorphMatch(int[] newBeat)
		{
		BestMorphMatchResult result = new BestMorphMatchResult();
		int type, i, bestMatch = 0, nextBest = 0, minShift = 0, shift, temp ;
		int nextShift2 ;
		double bestDiff2, nextDiff2;
		double beatDiff, minDiff = 0, nextDiff=10000 ;

		if(TypeCount == 0)
			{
			result.matchType = 0 ;
			result.matchIndex = 1000 ;		// Make sure there is no match so a new beat is
			result.shiftAdj = 0 ;			// created.
			return result;
			}

		// Compare the new beat to all type beat
		// types that have been saved.

		for(type = 0; type < TypeCount; ++type)
			{
			CompareBeatsResult cbr = CompareBeats(BeatTemplates[type], newBeat);
			beatDiff = cbr.metric;
			shift = cbr.shiftAdj;
			if(type == 0)
				{
				bestMatch = 0 ;
				minDiff = beatDiff ;
				minShift = shift ;
				}
			else if(beatDiff < minDiff)
				{
				nextBest = bestMatch ;
				nextDiff = minDiff ;
				bestMatch = type ;
				minDiff = beatDiff ;
				minShift = shift ;
				}
			else if((TypeCount > 1) && (type == 1))
				{
				nextBest = type ;
				nextDiff = beatDiff ;
				}
			else if(beatDiff < nextDiff)
				{
				nextBest = type ;
				nextDiff = beatDiff ;
				}
			}

		// If this beat was close to two different
		// templates, see if the templates which template
		// is the best match when no scaling is used.
		// Then check whether the two close types can be combined.

		if((minDiff < MATCH_LIMIT) && (nextDiff < MATCH_LIMIT) && (TypeCount > 1))
			{
			// Compare without scaling.
			CompareBeatsResult cbr = CompareBeats2(BeatTemplates[bestMatch], newBeat);
			bestDiff2 = cbr.metric;
			cbr = CompareBeats2(BeatTemplates[nextBest],newBeat);
			nextDiff2 = cbr.metric;
			nextShift2 = cbr.shiftAdj;
			if(nextDiff2 < bestDiff2)
				{
				temp = bestMatch ;
				bestMatch = nextBest ;
				nextBest = temp ;
				temp = (int) minDiff ;
				minDiff = nextDiff ;
				nextDiff = temp ;
				minShift = nextShift2 ;
				result.mi2 = bestDiff2 ;
				}
			else result.mi2 = nextDiff2 ;

			cbr = CompareBeats(BeatTemplates[bestMatch], BeatTemplates[nextBest]);
			beatDiff = cbr.metric;
			shift = cbr.shiftAdj;

			if((beatDiff < COMBINE_LIMIT) &&
				((result.mi2 < 1.0) || (MinimumBeatVariation(nextBest) == false)))
				{

				// Combine beats into bestMatch

				if(bestMatch < nextBest)
					{
					for(i = 0; i < bdacParas.BEATLGTH; ++i)
						{
						if((i+shift > 0) && (i + shift < bdacParas.BEATLGTH))
							{
							BeatTemplates[bestMatch][i] += BeatTemplates[nextBest][i+shift] ;
							BeatTemplates[bestMatch][i] >>= 1 ;
							}
						}

					if((BeatClassifications[bestMatch] == NORMAL) || (BeatClassifications[nextBest] == NORMAL))
						BeatClassifications[bestMatch] = NORMAL ;
					else if((BeatClassifications[bestMatch] == PVC) || (BeatClassifications[nextBest] == PVC))
						BeatClassifications[bestMatch] = PVC ;

					BeatCounts[bestMatch] += BeatCounts[nextBest] ;

					classifier.CombineDomData(nextBest,bestMatch) ;

					// Shift other templates over.

					for(type = nextBest; type < TypeCount-1; ++type)
						BeatCopy(type+1,type) ;

					}

				// Otherwise combine beats it nextBest.

				else
					{
					for(i = 0; i < bdacParas.BEATLGTH; ++i)
						{
						BeatTemplates[nextBest][i] += BeatTemplates[bestMatch][i] ;
						BeatTemplates[nextBest][i] >>= 1 ;
						}

					if((BeatClassifications[bestMatch] == NORMAL) || (BeatClassifications[nextBest] == NORMAL))
						BeatClassifications[nextBest] = NORMAL ;
					else if((BeatClassifications[bestMatch] == PVC) || (BeatClassifications[nextBest] == PVC))
						BeatClassifications[nextBest] = PVC ;

					BeatCounts[nextBest] += BeatCounts[bestMatch] ;

					classifier.CombineDomData(bestMatch,nextBest) ;

					// Shift other templates over.

					for(type = bestMatch; type < TypeCount-1; ++type)
						BeatCopy(type+1,type) ;


					bestMatch = nextBest ;
					}
				--TypeCount ;
				BeatClassifications[TypeCount] = UNKNOWN ;
				}
			}
		CompareBeatsResult cbr = CompareBeats2(BeatTemplates[bestMatch],newBeat);
		result.mi2 = cbr.metric;
		result.matchType = bestMatch ;
		result.matchIndex = minDiff ;
		result.shiftAdj = minShift ;
		return result;
		}
	
	public class BestMorphMatchResult {
		public int    matchType;
		public double matchIndex;
		public double mi2;
		public int    shiftAdj;
	}

	/**
	 * UpdateBeatType updates the beat template and features of a given beat type 
	 * using a new beat.
	 * 
	 * @param matchType
	 * @param newBeat
	 * @param mi2
	 * @param shiftAdj
	 */
	public void UpdateBeatType(int matchType,int[] newBeat, double mi2, int shiftAdj)
		{
		int i;

		// Update beats since templates were matched.

		for(i = 0; i < TypeCount; ++i)
			{
			if(i != matchType)
				++BeatsSinceLastMatch[i] ;
			else BeatsSinceLastMatch[i] = 0 ;
			}

		// If this is only the second beat, average it with the existing
		// template.

		if(BeatCounts[matchType] == 1)
			for(i = 0; i < bdacParas.BEATLGTH; ++i)
				{
				if((i+shiftAdj >= 0) && (i+shiftAdj < bdacParas.BEATLGTH))
					BeatTemplates[matchType][i] = (BeatTemplates[matchType][i] + newBeat[i+shiftAdj])>>1 ;
				}

		// Otherwise do a normal update.

		else
			UpdateBeat(BeatTemplates[matchType], newBeat, shiftAdj) ;

		// Determine beat features for the new average beat.

		AnalyzeBeatResult ar = beatAnalyzer.AnalyzeBeat(BeatTemplates[matchType]) ;

		BeatWidths[matchType] = ar.offset-ar.onset ;
		BeatCenters[matchType] = (ar.offset+ar.onset)/2 ;
		BeatBegins[matchType] = ar.beatBegin ;
		BeatEnds[matchType] = ar.beatEnd ;
		BeatAmps[matchType] = ar.amp ;

		++BeatCounts[matchType] ;

		for(i = MAXPREV-1; i > 0; --i)
			MIs[matchType][i] = MIs[matchType][i-1] ;
		MIs[matchType][0] = mi2 ;

		}

	/**
	 * GetDominantType returns the NORMAL beat type that has occurred most frequently.
	 * 
	 * @return
	 */
	public int GetDominantType()
		{
		int maxCount = 0, maxType = -1 ;
		int type, totalCount ;

		for(type = 0; type < bdacParas.MAXTYPES; ++type)
			{
			if((BeatClassifications[type] == NORMAL) && (BeatCounts[type] > maxCount))
				{
				maxType = type ;
				maxCount = BeatCounts[type] ;
				}
			}

		// If no normals are found and at least 300 beats have occurred, just use
		// the most frequently occurring beat.

		if(maxType == -1)
			{
			for(type = 0, totalCount = 0; type < TypeCount; ++type)
				totalCount += BeatCounts[type] ;
			if(totalCount > 300)
				for(type = 0; type < TypeCount; ++type)
					if(BeatCounts[type] > maxCount)
						{
						maxType = type ;
						maxCount = BeatCounts[type] ;
						}
			}

		return(maxType) ;
		}

	/**
	 * ClearLastNewType removes the last new type that was initiated
	 */
	public void ClearLastNewType()
		{
		if(TypeCount != 0)
			--TypeCount ;
		}

	/**
	 * GetBeatBegin returns the offset from the R-wave for the
	 * beginning of the beat (P-wave onset if a P-wave is found).
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatBegin(int type)
		{
		return(BeatBegins[type]) ;
		}

	/**
	 * GetBeatEnd returns the offset from the R-wave for the end of
	 * a beat (T-wave offset).
	 * 
	 * @param type
	 * @return
	 */
	public int GetBeatEnd(int type)
		{
		return(BeatEnds[type]) ;
		}

	/**
	 * DomCompare2 and DomCompare return similarity indexes between a given
	 * beat and the dominant normal type or a given type and the dominant
	 * normal type.
	 * 
	 * @param newType
	 * @param domType
	 * @return
	 */
	public double DomCompare2(int[] newBeat, int domType)
		{
		return(CompareBeats2(BeatTemplates[Math.max(domType, 0)],newBeat).metric) ;
		}

	/**
	 * DomCompare2 and DomCompare return similarity indexes between a given
	 * beat and the dominant normal type or a given type and the dominant
	 * normal type.
	 * 
	 * @param newType
	 * @param domType
	 * @return
	 */
	public double DomCompare(int newType, int domType)
		{
		return(CompareBeats2(BeatTemplates[Math.min(Math.max(domType, 0), bdacParas.MAXTYPES-1)],BeatTemplates[Math.min(Math.max(newType, 0), bdacParas.MAXTYPES-1)]).metric) ;
		}

	/**
	 * BeatCopy copies beat data from a source beat to a destination beat.
	 * 
	 * @param srcBeat
	 * @param destBeat
	 */
	public void BeatCopy(int srcBeat, int destBeat)
		{
		int i ;

		// Copy template.

		for(i = 0; i < bdacParas.BEATLGTH; ++i)
			BeatTemplates[destBeat][i] = BeatTemplates[srcBeat][i] ;

		// Move feature information.

		BeatCounts[destBeat] = BeatCounts[srcBeat] ;
		BeatWidths[destBeat] = BeatWidths[srcBeat] ;
		BeatCenters[destBeat] = BeatCenters[srcBeat] ;
		for(i = 0; i < MAXPREV; ++i)
			{
			postClassifier.PostClass[destBeat][i] = postClassifier.PostClass[srcBeat][i] ;
			postClassifier.PCRhythm[destBeat][i] = postClassifier.PCRhythm[srcBeat][i] ;
			}

		BeatClassifications[destBeat] = BeatClassifications[srcBeat] ;
		BeatBegins[destBeat] = BeatBegins[srcBeat] ;
		BeatEnds[destBeat] = BeatBegins[srcBeat] ;
		BeatsSinceLastMatch[destBeat] = BeatsSinceLastMatch[srcBeat];
		BeatAmps[destBeat] = BeatAmps[srcBeat] ;

		// Adjust data in dominant beat monitor.

		classifier.AdjustDomData(srcBeat,destBeat) ;
		}

	/**
	 * Minimum beat variation returns a 1 if the previous eight beats
	 * have all had similarity indexes less than 0.5.
	 * 
	 * @param type
	 * @return
	 */
	public boolean MinimumBeatVariation(int type)
		{
		int i ;
		for(i = 0; i < bdacParas.MAXTYPES; ++i)
			if(MIs[type][i] > 0.5)
				i = bdacParas.MAXTYPES+2 ;
		return(i == bdacParas.MAXTYPES);
		}

	/**
	 * WideBeatVariation returns true if the average similarity index
	 * for a given beat type to its template is greater than WIDE_VAR_LIMIT.
	 * 
	 * @param type
	 * @return
	 */
	public boolean WideBeatVariation(int type)
		{
		int i, n ;
		double aveMI ;

		n = BeatCounts[type] ;
		if(n > 8)
			n = 8 ;

		for(i = 0, aveMI = 0; i <n; ++i)
			aveMI += MIs[type][i] ;

		aveMI /= n ;
		return (aveMI > WIDE_VAR_LIMIT) ;
		}
	}
