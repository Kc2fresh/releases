package extractionUtils

import scala.collection.mutable
import scala.io.Source
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import org.slf4j.LoggerFactory
import Word2vec.logger
import java.io._
import edu.arizona.sista.utils.MathUtils

/**
 * Implements similarity metrics using the word2vec matrix
 * IMPORTANT: In our implementation, words are lower cased but NOT lemmatized or stemmed
 * User: mihais, dfried
 * Date: 11/25/13
 *
 */

// matrixConstructor is lazy, meant to save memory space if we're caching features
class Word2vec(matrixConstructor: => Map[String, Array[Double]]) {

  lazy val dimensions = matrix.values.head.size

  /** alternate constructor to allow loading from a single target vectors file, possibly with a set of words to constrain the vocab */
  def this(mf: String, wordsToUse: Option[Set[String]] = None) = {
    this(Word2vec.loadMatrix(mf, wordsToUse)._1)
  }

  // construct the matrix for this instance lazily by calling the constructor
  lazy val matrix : Map[String, Array[Double]] = matrixConstructor


  def saveMatrix(mf: String) {
    val pw = new PrintWriter(mf)
    pw.println(s"${matrix.size}, $dimensions")
    for ((word, vec) <- matrix) {
      val strRep = vec.map(_.formatted("%.6f")).mkString(" ")
      pw.println(s"$word $strRep")
    }
    pw.close
  }

  /** Normalizes this vector to length 1, in place */
  private def norm(weights:Array[Double]) {
    var i = 0
    var len = 0.0
    while(i < weights.length) {
      len += weights(i) * weights(i)
      i += 1
    }
    len = math.sqrt(len)
    i = 0
    if(len != 0) {
      while (i < weights.length) {
        weights(i) /= len
        i += 1
      }
    }
  }

  /**
   * Computes the similarity between two given words
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   * @param w1 The first word
   * @param w2 The second word
   * @return The cosine similarity of the two corresponding vectors
   */
  def similarity(w1:String, w2:String):Double = {
    val v1o = matrix.get(w1)
    if(v1o.isEmpty) return -1
    val v2o = matrix.get(w2)
    if(v2o.isEmpty) return -1
    dotProduct(v1o.get, v2o.get)
  }

  private def dotProduct(v1:Array[Double], v2:Array[Double]):Double = {
    assert(v1.length == v2.length) //should we always assume that v2 is longer? perhaps set shorter to length of longer...
    var sum = 0.0
    var i = 0
    while(i < v1.length) {
      sum += v1(i) * v2(i)
      i += 1
    }
    sum
  }

  /** Adds the content of src to dest, in place */
  private def add(dest:Array[Double], src:Array[Double]) {
    var i = 0
    while(i < dimensions) {
      dest(i) += src(i)
      i += 1
    }
  }

  /** filterPredicate: if passed, only returns words that match the predicate */
  def mostSimilarWords(v: Array[Double], howMany:Int, filterPredicate: Option[String => Boolean]):List[(String,  Double)] = {
    val words = filterPredicate match {
      case None => matrix.keys
      case Some(p) => matrix.keys.filter(p)
    }
    MathUtils.nBest[String](word => dotProduct(v, matrix(word)))(words, howMany)
  }

  /**
   * Finds the words most similar to this set of inputs
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   */
  def mostSimilarWords(words:Set[String], howMany:Int):List[(String, Double)] = {
    val v = new Array[Double](dimensions)
    var found = false
    for(w1 <- words) {
      val w = Word2vec.sanitizeWord(w1)         // sanitize words
      val vo = matrix.get(w)
      if(vo.isDefined) {
        found = true
        add(v, vo.get)
      }
    }
    if(! found) return List()
    Word2vec.norm(v)
    mostSimilarWords(v, howMany, None)
  }

  def mostSimilarWords(word: String, howMany: Int, filterPredicate: Option[String => Boolean] = None): List[(String,
    Double)] = matrix.get(word) match {
    case Some(v) => mostSimilarWords(v, howMany, filterPredicate)
    case None => List()
  }

  private def makeCompositeVector(t:Iterable[String]):Array[Double] = {
    val vTotal = new Array[Double](dimensions)
    for(s <- t) {
      val v = matrix.get(s)
      if(v.isDefined) add(vTotal, v.get)
    }
    Word2vec.norm(vTotal)
    vTotal
  }

  /**
   * Computes the cosine similarity between two texts, according to the word2vec matrix
   * IMPORTANT: t1, t2 must be arrays of words, not lemmas!
   */
  def textSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    sanitizedTextSimilarity(st1, st2)
  }

  /**
   * Computes the cosine similarity between two texts, according to the word2vec matrix
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   */
  def sanitizedTextSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    val v1 = makeCompositeVector(t1)
    val v2 = makeCompositeVector(t2)
    dotProduct(v1, v2)
  }


  /**
   * Similar to textSimilarity, but using the multiplicative heuristic of Levy and Goldberg (2014)
   * IMPORTANT: t1, t2 must be arrays of words, not lemmas!
   */
  def multiplicativeTextSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    multiplicativeSanitizedTextSimilarity(st1, st2)
  }

  /**
   * Similar to sanitizedTextSimilarity, but but using the multiplicative heuristic of Levy and Goldberg (2014)
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   * @return Similarity value
   */
  def multiplicativeSanitizedTextSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    var sim = 1.0
    for(w1 <- t1) {
      for(w2 <- t2) {
        // no need to add the log sim if identical (log(1) == 0)
        if(w1 != w2) {
          val v1 = matrix.get(w1)
          val v2 = matrix.get(w2)
          if(v1.isDefined && v2.isDefined) {
            // *multiply* rather than add similarities!
            sim *= dotProduct(v1.get, v2.get)
          }
        }
      }
    }
    sim
  }

  def logMultiplicativeTextSimilarity(t1: Iterable[String],
                                      t2: Iterable[String],
                                      method: Symbol = 'linear,
                                      normalize: Boolean = false): Double = {
    val st1 = t1.map(Word2vec.sanitizeWord(_))
    val st2 = t2.map(Word2vec.sanitizeWord(_))
    logMultiplicativeSanitizedTextSimilarity(st1, st2, method, normalize)
  }

  def logMultiplicativeSanitizedTextSimilarity(t1:Iterable[String],
                                               t2:Iterable[String],
                                               method: Symbol = 'linear,
                                               normalize: Boolean = false):Double = {
    val t1Vecs = t1.flatMap(matrix.get) // this will drop any words that don't have vectors
    val t2Vecs = t2.flatMap(matrix.get)
    val sims = for {
      v1 <- t1Vecs
      v2 <- t2Vecs
      cosSim = dotProduct(v1, v2)
      toYield = method match {
        case 'linear => math.log(cosSim + 1)
        case 'linear_scaled => math.log((cosSim + 1) / 2)
        case 'angular => math.log(1 - (math.acos(math.min(1, math.max(-1, cosSim))) / math.Pi))
        case _ => throw new Exception(s"invalid method $method")
      }
    } yield toYield
    val sum = sims.sum
    if (normalize && t2Vecs.nonEmpty)
      sum / t2Vecs.size
    else
      sum
  }

  /**
   * Finds the maximum word2vec similarity between any two words in these two texts
   * IMPORTANT: IMPORTANT: t1, t2 must be arrays of words, not lemmas!
   */
  def maxSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    sanitizedMaxSimilarity(st1, st2)
  }

  def minSimilarity(t1: Iterable[String], t2: Iterable[String]): Double = {
    val st1 = t1.map(Word2vec.sanitizeWord(_))
    val st2 = t2.map(Word2vec.sanitizeWord(_))
    sanitizedMinSimilarity(st1, st2)
  }

  /**
   * Finds the maximum word2vec similarity between any two words in these two texts
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   */
  def sanitizedMaxSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    var max = Double.MinValue
    for(s1 <- t1) {
      val v1 = matrix.get(s1)
      if(v1.isDefined) {
        for(s2 <- t2) {
          val v2 = matrix.get(s2)
          if(v2.isDefined) {
            val s = dotProduct(v1.get, v2.get)
            if(s > max) max = s
          }
        }
      }
    }
    max
  }

  /**
   * Finds the minimum word2vec similarity between any two words in these two texts
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   */
  def sanitizedMinSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    var min = Double.MaxValue
    for(s1 <- t1) {
      val v1 = matrix.get(s1)
      if(v1.isDefined) {
        for(s2 <- t2) {
          val v2 = matrix.get(s2)
          if(v2.isDefined) {
            val s = dotProduct(v1.get, v2.get)
            if(s < min) min = s
          }
        }
      }
    }
    min
  }

  /**
   * Finds the average word2vec similarity between any two words in these two texts
   * IMPORTANT: words here must be words not lemmas!
   */
  def avgSimilarity(t1:Iterable[String], t2:Iterable[String]):Double = {
    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    val (score, pairs) = sanitizedAvgSimilarity(st1, st2)

    score
  }

  def avgSimilarityReturnTop(t1:Iterable[String], t2:Iterable[String]):(Double, Array[(Double, String, String)]) = {
    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    val (score, pairs) = sanitizedAvgSimilarity(st1, st2)

    val sorted = pairs.sortBy(- _._1).toArray
    //if (sorted.size > 10) return (score, sorted.slice(0, 10))     // Commented out -- return all pairs for UASupport structure (it can filter them if it wants)
    (score, sorted)
  }

  /**
   * Finds the average word2vec similarity between any two words in these two texts
   * IMPORTANT: words here must already be normalized using Word2vec.sanitizeWord()!
   * Changelog: (Peter/June 4/2014) Now returns words list of pairwise scores, for optional answer justification.
   */
  def sanitizedAvgSimilarity(t1:Iterable[String], t2:Iterable[String]):(Double, ArrayBuffer[(Double, String, String)]) = {
    // Top words
    var pairs = new ArrayBuffer[(Double, String, String)]

    var avg = 0.0
    var count = 0
    for(s1 <- t1) {
      val v1 = matrix.get(s1)
      if(v1.isDefined) {
        for(s2 <- t2) {
          val v2 = matrix.get(s2)
          if(v2.isDefined) {
            val s = dotProduct(v1.get, v2.get)
            avg += s
            count += 1

            // Top Words
            pairs.append ( (s, s1, s2) )
          }
        }
      }
    }
    if(count != 0) (avg / count, pairs)
    else (0, pairs)
  }

  /**
    * Finds the distribution of word2vec similarities between any two words in these two texts
    * IMPORTANT: words here must be words not lemmas!
    * Currently, bins are discretized as:
    *   [-1, -0.75), [-0.75, -0.25), [-0.25, 0.25), [0.25, 0.75), [0.75, 1.0]
    */
  def binnedSimilarity(t1:Iterable[String], t2:Iterable[String]):Array[Double] = {

    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    val (bins, pairs) = sanitizedBinnedSimilarity(st1, st2)

    bins
  }

  def sanitizedBinnedSimilarity(t1:Iterable[String], t2:Iterable[String]):(Array[Double], ArrayBuffer[(Double, String, String)]) = {
    val binThresholds = Array(0.45, 0.8, 3.0)
    val pairs = new ArrayBuffer[(Double, String, String)]
    val scores = new ArrayBuffer[Double]

    for (s1 <- t1) {
      val v1 = matrix.get(s1)
      if (v1.isDefined) {
        for (s2 <- t2) {
          val v2 = matrix.get(s2)
          if (v2.isDefined) {
            val s = checkRange(dotProduct(v1.get, v2.get))
            pairs.append((s, s1, s2))
            scores.append(s)
          }
        }
      }
    }

    val normalizedBins = makeBins(binThresholds, scores.toArray)

    (normalizedBins, pairs)
  }

  def binnedMaxSimilarity(t1:Iterable[String], t2:Iterable[String]):Array[Double] = {

    val st1 = new ArrayBuffer[String]()
    t1.foreach(st1 += Word2vec.sanitizeWord(_))
    val st2 = new ArrayBuffer[String]()
    t2.foreach(st2 += Word2vec.sanitizeWord(_))
    val (bins, pairs) = sanitizedBinnedMaxSimilarity(st1, st2)

    bins
  }

  def sanitizedBinnedMaxSimilarity(t1:Iterable[String], t2:Iterable[String]):(Array[Double], ArrayBuffer[(Double, String, String)]) = {

    val binThresholds = Array(0.45, 0.8, 3.0)

    val pairs = new ArrayBuffer[(Double, String, String)]

    val t1Maxes = Array.fill[Double](t1.size)(0.0)
    val t2Maxes = Array.fill[Double](t2.size)(0.0)

    var count:Double = 0.0
    for ((s1, s1i) <- t1.zipWithIndex) {

      val v1 = matrix.get(s1)
      if (v1.isDefined) {
        for ((s2, s2i) <- t2.zipWithIndex) {
          val v2 = matrix.get(s2)
          if (v2.isDefined) {
            val s = checkRange(dotProduct(v1.get, v2.get))
            t1Maxes(s1i) = math.max(s, t1Maxes(s1i))
            t2Maxes(s2i) = math.max(s, t2Maxes(s2i))
            pairs.append((s, s1, s2))
          }
        }
      }
    }

    // max bins
    // Find the index of the bin the score belongs in
    val maxBins = makeBins(binThresholds, t1Maxes ++ t2Maxes)

    (maxBins, pairs)
  }

  def checkRange(dbl: Double) = {
    if (dbl.isNaN || dbl == Double.MinValue || dbl == Double.MaxValue) 0.0 else dbl
  }


  def makeBins (binThresholds:Array[Double], toBin:Array[Double]): Array[Double] = {
    val numBins = binThresholds.length
    val bins = Array.fill[Double](numBins)(0.0)

    if (toBin.isEmpty) return bins

    var count:Double = 0.0

    val bin1 = for {
      value <- toBin
      (threshold, i) <- binThresholds.zipWithIndex
      if value <= threshold
    } yield i
    // Keep the first one it matched
    val binId = if (!bin1.isEmpty) bin1.head else -1
    // Catch errors!
    if (binId == -1) {
      println ("Couldn't match a bin:\nbin1 = " + bin1.mkString(",") + " toBin = " + toBin.mkString(","))
      sys.exit(0)
    }

    // Store
    bins(binId) += 1.0
    count += 1.0
    val normalizedBins = if (count != 0.0) bins.map(e => e/count) else bins

    normalizedBins
  }

  /**
   * for a sequence of (word, weight) pairs, interpolate the vectors corresponding to the words by their respective
   * weights, and normalize the resulting vector
   */
  def interpolate(wordsAndWeights: Iterable[(String, Double)]) = {
    // create a vector to store the weighted sum
    val v = new Array[Double](dimensions)
    for ((word, p) <- wordsAndWeights) {
      // get this word's vector, scaled by the weight
      val scaled = for {
        x <- matrix(word)
      } yield x * p
      // add it in place to the sum vector
      add(v, scaled)
    }
    Word2vec.norm(v)
    v
  }
}

object Word2vec {
  val logger = LoggerFactory.getLogger(classOf[Word2vec])

  /**
   * Normalizes words for word2vec
   * @param uw A word (NOT lemma)
   * @return The normalized form of the word
   */
  def sanitizeWord(uw:String, keepNumbers:Boolean = true):String = {
    val w = uw.toLowerCase()

    // skip parens from corenlp
    if(w == "-lrb-" || w == "-rrb-" || w == "-lsb-" || w == "-rsb-") {
      return ""
    }

    // skip URLS
    if(w.startsWith("http") || w.contains(".com") || w.contains(".org")) //added .com and .org to cover more urls (becky)
      return ""

    // normalize numbers to a unique token
    if(isNumber(w)) {
      if(keepNumbers) return "xnumx"
      else return ""
    }

    // remove all non-letters; convert letters to lowercase
    val os = new collection.mutable.StringBuilder()
    var i = 0
    while(i < w.length) {
      val c = w.charAt(i)
      // added underscore since it is our delimiter for dependency stuff...
      if(Character.isLetter(c) || c == '_') os += c
      i += 1
    }
    os.toString()
  }

  def isNumber(w:String):Boolean = {
    var i = 0
    var foundDigit = false
    while(i < w.length) {
      val c = w.charAt(i)
      if(! Character.isDigit(c) &&
        c != '-' && c != '+' &&
        c != ',' && c != '.' &&
        c != '/' && c != '\\')
        return false
      if(Character.isDigit(c))
        foundDigit = true
      i += 1
    }
    foundDigit
  }

  /** Normalizes this vector to length 1, in place */
  private def norm(weights:Array[Double]) {
    var i = 0
    var len = 0.0
    while(i < weights.length) {
      len += weights(i) * weights(i)
      i += 1
    }
    len = math.sqrt(len)
    i = 0
    if (len != 0) {
      while(i < weights.length) {
        weights(i) /= len
        i += 1
      }
    }
  }

  private def loadMatrix(mf:String, wordsToUse: Option[Set[String]]):(Map[String, Array[Double]], Int) = {
    logger.debug("Started to load word2vec matrix from file " + mf + "...")
    val m = new collection.mutable.HashMap[String, Array[Double]]()
    var first = true
    var dims = 0
    for((line, index) <- Source.fromFile(mf, "iso-8859-1").getLines().zipWithIndex) {
      val bits = line.split("\\s+")
      if(first) {
        dims = bits(1).toInt
        first = false
      } else {
          if (bits.length != dims + 1) {
              println(s"${bits.length} != ${dims + 1} found on line ${index + 1}")
              }
        assert(bits.length == dims + 1)
        val w = bits(0)
        if (wordsToUse.isEmpty || wordsToUse.get.contains(w)) {
          val weights = new Array[Double](dims)
          var i = 0
          while(i < dims) {
            weights(i) = bits(i + 1).toDouble
            i += 1
          }
          norm(weights)
          m.put(w, weights)
        }
      }
    }
    logger.debug("Completed matrix loading.")
    (m.toMap, dims)
  }


  def main(args:Array[String]) {
    val w2v = new Word2vec(args(0), None)

    var input = ""
    while (input != "EXIT") {
      input = scala.io.StdIn.readLine("Retrieve 20 words most similar to (or EXIT): ")
      for(t <- w2v.mostSimilarWords(Set(input), 20)) {
        println(t._1 + " " + t._2)
      }
    }


    // DEBUG
//    val t1 = List("a", "delicious", "apple")
//    val t2 = List("the", "tasty", "pear")
//    val t3 = List("computer", "oxygen")
//    println("Text similarity: " + w2v.sanitizedTextSimilarity(t1, t2))
//    println("Text similarity: " + w2v.sanitizedTextSimilarity(t1, t3))
//    println("Max similarity: " + w2v.sanitizedMaxSimilarity(t1, t2))
//    println("Avg similarity: " + w2v.sanitizedAvgSimilarity(t1, t2))
//
//    val bs12 = w2v.sanitizedBinnedSimilarity(t1, t2)
//    val bs13 = w2v.sanitizedBinnedSimilarity(t1, t3)
//    println("Binned Similarities:" + bs12._1.zipWithIndex.mkString(", ") + "\n" + bs12._2)
//    println("Binned Similarities:" + bs13._1.zipWithIndex.mkString(", ") + "\n" + bs13._2)
  }
}
