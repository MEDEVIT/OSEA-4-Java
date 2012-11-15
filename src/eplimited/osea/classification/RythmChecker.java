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

import static eplimited.osea.classification.ECGCODES.*;

import java.util.Arrays;

import eplimited.osea.detection.QRSDetectorParameters;

/**
 * Rythmchk.cpp contains functions for classifying RR intervals as either
 * NORMAL, PVC, or UNKNOWN.  Intervals classified as NORMAL are presumed to
 * end with normal beats, intervals classified as PVC are presumed to end
 * with a premature contraction, and intervals classified as unknown do
 * not fit any pattern that the rhythm classifier recognizes.
 * 
 * NORMAL intervals can be part of a normal (regular) rhythm, normal beats
 * following a premature beats, or normal beats following runs of ventricular
 * beats.  PVC intervals can be short intervals following a regular rhythm,
 * short intervals that are part of a run of short intervals following a
 * regular rhythm, or short intervals that are part of a bigeminal rhythm.
 */
public class RythmChecker 
	{

	private static final int QQ = 0 ; // Unknown-Unknown interval.
	private static final int NN = 1 ; // Normal-Normal interval.
	private static final int NV = 2 ; // Normal-PVC interval.
	private static final int VN = 3 ; // PVC-Normal interval.
	private static final int VV = 4 ; // PVC-PVC interval.
	
	private static final int RBB_LENGTH	= 8 ;
	private static final int LEARNING =0 ;
	private static final int READY = 1;
	
	private int BRADY_LIMIT ;
	
	private int[] RRBuffer      = new int[RBB_LENGTH] ;
	private int[] RRTypes       = new int[RBB_LENGTH] ;
	private int   BeatCount     = 0 ;
	private int   ClassifyState = LEARNING ;

	private boolean BigeminyFlag ;
	
	/**
	 * Create a new rythmChecker with the given parameters.
	 * @param qrsDetectorParameters The sampleRate-dependent parameters
	 */
	public RythmChecker(QRSDetectorParameters qrsDetectorParameters) 
		{
		BRADY_LIMIT = qrsDetectorParameters.MS1500 ;
		}
	
	/**
	 * RhythmChk() takes an R-to-R interval as input and, based on previous R-to-R
	 * intervals, classifys the interval as NORMAL, PVC, or UNKNOWN.
	 * 
	 * @param rr An R-toR interval
	 * @return The classification
	 */
	public int RhythmChk(int rr)
		{
		int i, regular = 1 ;
		int NNEst, NVEst ;
	
		BigeminyFlag = false ;
	
		// Wait for at least 4 beats before classifying anything.
	
		if(BeatCount < 4)
			{
			if(++BeatCount == 4)
				ClassifyState = READY ;
			}
	
		// Stick the new RR interval into the RR interval Buffer.
	
		for(i = RBB_LENGTH-1; i > 0; --i)
			{
			RRBuffer[i] = RRBuffer[i-1] ;
			RRTypes[i] = RRTypes[i-1] ;
			}
	
		RRBuffer[0] = rr ;
	
		if(ClassifyState == LEARNING)
			{
			RRTypes[0] = QQ ;
			return(UNKNOWN) ;
			}
	
		// If we couldn't tell what the last interval was...
	
		if(RRTypes[1] == QQ)
			{
			for(i = 0, regular = 1; i < 3; ++i)
				if(RRMatch(RRBuffer[i],RRBuffer[i+1]) == false)
					regular = 0 ;
	
			// If this, and the last three intervals matched, classify
			// it as Normal-Normal.
	
			if(regular == 1)
				{
				RRTypes[0] = NN ;
				return(NORMAL) ;
				}
	
			// Check for bigeminy.
			// Call bigeminy if every other RR matches and
			// consecutive beats do not match.
	
			for(i = 0, regular = 1; i < 6; ++i)
				if(RRMatch(RRBuffer[i],RRBuffer[i+2]) == false)
					regular = 0 ;
			for(i = 0; i < 6; ++i)
				if(RRMatch(RRBuffer[i],RRBuffer[i+1]))
					regular = 0 ;
	
			if(regular == 1)
				{
				BigeminyFlag = true ;
				if(RRBuffer[0] < RRBuffer[1])
					{
					RRTypes[0] = NV ;
					RRTypes[1] = VN ;
					return(PVC) ;
					}
				else
					{
					RRTypes[0] = VN ;
					RRTypes[1] = NV ;
					return(NORMAL) ;
					}
				}
	
			// Check for NNVNNNV pattern.
	
			if(RRShort(RRBuffer[0],RRBuffer[1]) && RRMatch(RRBuffer[1],RRBuffer[2])
				&& RRMatch(RRBuffer[2]*2,RRBuffer[3]+RRBuffer[4]) &&
				RRMatch(RRBuffer[4],RRBuffer[0]) && RRMatch(RRBuffer[5],RRBuffer[2]))
				{
				RRTypes[0] = NV ;
				RRTypes[1] = NN ;
				return(PVC) ;
				}
	
			// If the interval is not part of a
			// bigeminal or regular pattern, give up.
	
			else
				{
				RRTypes[0] = QQ ;
				return(UNKNOWN) ;
				}
			}
	
		// If the previous two beats were normal...
	
		else if(RRTypes[1] == NN)
			{
	
			if(RRShort2(RRBuffer,RRTypes))
				{
				if(RRBuffer[1] < BRADY_LIMIT)
					{
					RRTypes[0] = NV ;
					return(PVC) ;
					}
				else RRTypes[0] = QQ ;
					return(UNKNOWN) ;
				}
	
	
			// If this interval matches the previous interval, then it
			// is regular.
	
			else if(RRMatch(RRBuffer[0],RRBuffer[1]))
				{
				RRTypes[0] = NN ;
				return(NORMAL) ;
				}
	
			// If this interval is short..
	
			else if(RRShort(RRBuffer[0],RRBuffer[1]))
				{
	
				// But matches the one before last and the one before
				// last was NN, this is a normal interval.
	
				if(RRMatch(RRBuffer[0],RRBuffer[2]) && (RRTypes[2] == NN))
					{
					RRTypes[0] = NN ;
					return(NORMAL) ;
					}
	
				// If the rhythm wasn't bradycardia, call it a PVC.
	
				else if(RRBuffer[1] < BRADY_LIMIT)
					{
					RRTypes[0] = NV ;
					return(PVC) ;
					}
	
				// If the regular rhythm was bradycardia, don't assume that
				// it was a PVC.
	
				else
					{
					RRTypes[0] = QQ ;
					return(UNKNOWN) ;
					}
				}
	
			// If the interval isn't normal or short, then classify
			// it as normal but don't assume normal for future
			// rhythm classification.
	
			else
				{
				RRTypes[0] = QQ ;
				return(NORMAL) ;
				}
			}
	
		// If the previous beat was a PVC...
	
		else if(RRTypes[1] == NV)
			{
	
			if(RRShort2(Arrays.copyOfRange(RRBuffer, 1, RRBuffer.length),
						Arrays.copyOfRange(RRTypes, 1, RRTypes.length)))
				{
	
				if(RRMatch(RRBuffer[0],RRBuffer[1]))
					{
					RRTypes[0] = NN ;
					RRTypes[1] = NN ;
					return(NORMAL) ;
					}
				else if(RRBuffer[0] > RRBuffer[1])
					{
					RRTypes[0] = VN ;
					return(NORMAL) ;
					}
				else
					{
					RRTypes[0] = QQ ;
					return(UNKNOWN) ;
					}
	
	
				}
	
			// If this interval matches the previous premature
			// interval assume a ventricular couplet.
	
			else if(RRMatch(RRBuffer[0],RRBuffer[1]))
				{
				RRTypes[0] = VV ;
				return(PVC) ;
				}
	
			// If this interval is larger than the previous
			// interval, assume that it is NORMAL.
	
			else if(RRBuffer[0] > RRBuffer[1])
				{
				RRTypes[0] = VN ;
				return(NORMAL) ;
				}
	
			// Otherwise don't make any assumputions about
			// what this interval represents.
	
			else
				{
				RRTypes[0] = QQ ;
				return(UNKNOWN) ;
	         }
			}
	
		// If the previous beat followed a PVC or couplet etc...
	
		else if(RRTypes[1] == VN)
			{
	
			// Find the last NN interval.
	
			for(i = 2; (i < RBB_LENGTH) && (RRTypes[i] != NN) ; ++i) ;
	
			// If there was an NN interval in the interval buffer...
			if(i != RBB_LENGTH)
				{
				NNEst = RRBuffer[i] ;
	
				// and it matches, classify this interval as NORMAL.
	
				if(RRMatch(RRBuffer[0],NNEst))
					{
					RRTypes[0] = NN ;
					return(NORMAL) ;
					}
				}
	
			else NNEst = 0 ;
			for(i = 2; (i < RBB_LENGTH) && (RRTypes[i] != NV); ++i) ;
			if(i != RBB_LENGTH)
				NVEst = RRBuffer[i] ;
			else NVEst = 0 ;
			if((NNEst == 0) && (NVEst != 0))
				NNEst = (RRBuffer[1]+NVEst) >> 1 ;
	
			// NNEst is either the last NN interval or the average
			// of the most recent NV and VN intervals.
	
			// If the interval is closer to NN than NV, try
			// matching to NN.
	
			if((NVEst != 0) &&
				(Math.abs(NNEst - RRBuffer[0]) < Math.abs(NVEst - RRBuffer[0])) &&
				RRMatch(NNEst,RRBuffer[0]))
				{
				RRTypes[0] = NN ;
				return(NORMAL) ;
				}
	
			// If this interval is closer to NV than NN, try
			// matching to NV.
	
			else if((NVEst != 0) &&
				(Math.abs(NNEst - RRBuffer[0]) > Math.abs(NVEst - RRBuffer[0])) &&
				RRMatch(NVEst,RRBuffer[0]))
				{
				RRTypes[0] = NV ;
				return(PVC) ;
				}
	
			// If equally close, or we don't have an NN or NV in the buffer,
			// who knows what it is.
	
			else
				{
				RRTypes[0] = QQ ;
				return(UNKNOWN) ;
				}
			}
	
		// Otherwise the previous interval must have been a VV
	
		else
			{
	
			// Does this match previous VV.
	
			if(RRMatch(RRBuffer[0],RRBuffer[1]))
				{
				RRTypes[0] = VV ;
				return(PVC) ;
				}
	
			// If this doesn't match a previous VV interval, assume
			// any new interval is recovery to Normal beat.
	
			else
				{
				if(RRShort(RRBuffer[0],RRBuffer[1]))
					{
					RRTypes[0] = QQ ;
					return(UNKNOWN) ;
					}
				else
					{
					RRTypes[0] = VN ;
					return(NORMAL) ;
					}
				}
			}
		}
	
	/**
	 * RRMatch() test whether two intervals are within 12.5% of their mean.
	 * 
	 * @param rr0 The first interval
	 * @param rr1 The second interval
	 * @return If they are within 12.5% of their mean.
	 */
	private boolean RRMatch(int rr0,int rr1)
		{
		return(Math.abs(rr0-rr1) < ((rr0+rr1)>>3));
		}
	
	/**
	 * RRShort() tests whether an interval is less than 75% of the previous interval.
	 * 
	 * @param rr0 The first interval
	 * @param rr1 The second interval
	 * @return If the interval is less than 75% of the previous interval
	 */
	private boolean RRShort(int rr0, int rr1)
		{
		return(rr0 < rr1-(rr1>>2));
		}
	
	/**
	 * IsBigeminy() allows external access to the bigeminy flag to check whether
	 * a bigeminal rhythm is in progress.
	 * 
	 * @return The bigeminy flag
	 */
	public boolean IsBigeminy()
		{
		return(BigeminyFlag) ;
		}
	
	/**
	 * Check for short interval in very regular rhythm.
	 * 
	 * @param rrIntervals
	 * @param rrTypes
	 * @return
	 */
	private boolean RRShort2(int[] rrIntervals, int[] rrTypes)
		{
		int rrMean = 0, i, nnCount ;
	
		for(i = 1, nnCount = 0; (i < 7) && (nnCount < 4); ++i)
			if(rrTypes[i] == NN)
				{
				++nnCount ;
				rrMean += rrIntervals[i] ;
				}
	
		// Return if there aren't at least 4 normal intervals.
	
		if(nnCount != 4)
			return(false) ;
		rrMean >>= 2 ;
	
	
		for(i = 1, nnCount = 0; (i < 7) && (nnCount < 4); ++i)
			if(rrTypes[i] == NN)
				{
				if(Math.abs(rrMean-rrIntervals[i]) > (rrMean>>4))
					i = 10 ;
				}
	
		return((i < 9) && (rrIntervals[0] < (rrMean - (rrMean>>3))));
		}
	}
