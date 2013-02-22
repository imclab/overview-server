/**
 * ConnectedComponentDocTreeBuilder.scala
 * Document clustering algorithm based on connected components
 * Original algorithm by Stephen Ingram
 * Also, a Hybrid k-means/connected components algorithm
 * 
 * DEPRECATED - this algorithm is no longer used, as it produces too much "dust." Recursive k-means is superior. 
 *
 * Overview Project, created July 2012
 *
 * @author Jonathan Stray
 *
 */

package org.overviewproject.clustering

import scala.collection.mutable.Set
import org.overviewproject.util.DocumentSetCreationJobStateDescription.ClusteringLevel
import org.overviewproject.util.Progress.{ Progress, ProgressAbortFn, makeNestedProgress, NoProgressReporting }
import org.overviewproject.clustering.ClusterTypes._
import scala.util.control.Breaks._

class ConnectedComponentDocTreeBuilder(protected val docVecs: DocumentSetVectors) {

  private val distanceFn = (a:DocumentVector,b:DocumentVector) => DistanceFn.CosineDistance(a,b) // can't use CosineDistance because of method overloading :(
  private var sampledEdges = new SampledEdges

  // Produces all docs reachable from a given start doc, given thresh
  // Unoptimized implementation, scans through all possible edges (N^2 total)
  private def allReachableDocs(thresh: Double, thisDoc: DocumentID, otherDocs: Set[DocumentID]): Iterable[DocumentID] = {
    for (
      otherDoc <- otherDocs;
      if distanceFn(docVecs(thisDoc), docVecs(otherDoc)) <= thresh
    ) yield otherDoc
  }

  // Same logic as above, but only looks through edges stored in sampledEdges
  private def sampledReachableDocs(thresh: Double, thisDoc: DocumentID, otherDocs: Set[DocumentID]): Iterable[DocumentID] = {
    val g = sampledEdges.get(thisDoc)
    if (g.isDefined) {
      for (
        (otherDoc, distance) <- g.get if otherDocs.contains(otherDoc) if distance <= thresh
      ) yield otherDoc
    } else {
      Nil
    }
  }

  // Returns an edge walking function suitable for ConnectedComponents, using the sampled edge set if we have it
  private def createEdgeEnumerator(thresh: Double) = {
    if (!sampledEdges.isEmpty)
      (doc: DocumentID, docSet: Set[DocumentID]) => sampledReachableDocs(thresh, doc, docSet)
    else
      (doc: DocumentID, docSet: Set[DocumentID]) => allReachableDocs(thresh, doc, docSet)
  }

  // Expand out the nodes of the tree by thresholding the documents in each and seeing if they split into components
  // Returns new set of leaf nodes
  private def ExpandTree(currentLeaves: List[DocTreeNode], thresh: Double) = {
    var nextLeaves = List[DocTreeNode]()

    for (node <- currentLeaves) {

      val childComponents = ConnectedComponents.AllComponents[DocumentID](node.docs, createEdgeEnumerator(thresh))

      if (childComponents.size == 1) {
        // lower threshold did not split this component, pass unchanged to next level
        nextLeaves = node :: nextLeaves
      } else {
        // lower threshold did split this component, create a child TreeNode for each resulting component
        for (component <- childComponents) {
          val newLeaf = new DocTreeNode(component)
          node.children += newLeaf
          nextLeaves = newLeaf :: nextLeaves
        }
      }
    }

    nextLeaves
  }

 // --- public ---
    
  def buildNodeSubtree(root:DocTreeNode, threshSteps: Seq[Double], progAbort: ProgressAbortFn) : Unit = {
    val numSteps: Double = threshSteps.size

    // intermediate levels created by successively thresholding all edges, (possibly) breaking each component apart
    var currentLeaves = List(root)

    breakable {
      for (curStep <- 1 to threshSteps.size - 2) {
        if (progAbort(Progress(curStep / numSteps, ClusteringLevel(curStep + 1)))) break
        currentLeaves = ExpandTree(currentLeaves, threshSteps(curStep))
      }
    }

    // bottom level thresh=0.0 is one leaf node for each document
    if (!progAbort(Progress((numSteps - 1) / numSteps, ClusteringLevel(numSteps.toInt)))) {
      for (node <- currentLeaves) {
        if (node.docs.size > 1) // don't expand if already one node
          node.children = node.docs.map(item => new DocTreeNode(Set(item)))
      }
    }   
  }
   
  // Steps distance thresh along given sequence. First step must always be 1 = full graph, 0 must always be last = leaves
  def BuildTree(root:DocTreeNode, threshSteps: Seq[Double], progAbort: ProgressAbortFn = NoProgressReporting): DocTreeNode = {
    require(threshSteps.head == 1.0)
    require(threshSteps.last == 0.0)
    require(threshSteps.forall(step => step >= 0 && step <= 1.0))

    // root thresh=1.0 is one node with all documents
    progAbort(Progress(0, ClusteringLevel(1)))

    buildNodeSubtree(root, threshSteps, progAbort)
    
    root
  }

  // version which sets root = all docs
  def BuildFullTree(threshSteps: Seq[Double], progAbort: ProgressAbortFn = NoProgressReporting): DocTreeNode = {
    val topLevel = Set(docVecs.keys.toSeq:_*)
    val root = new DocTreeNode(topLevel)
    BuildTree(root, threshSteps, progAbort)
  }

  
  def sampleCloseEdges(numEdgesPerDoc: Int): Unit = {
    sampledEdges = new EdgeSampler(docVecs, distanceFn).edges(numEdgesPerDoc)
  }


}

// Take a node and create K children.
// Encapsulates parameters of our our-means clustering
class KMeansNodeSplitter(protected val docVecs: DocumentSetVectors, protected val k:Int) {
  private val km = new KMeansDocuments(docVecs)
  km.seedClusterSize = 1
  km.maxIterations = 15
  
  def splitNode(node:DocTreeNode) : Unit = {  
    val stableDocs = node.docs.toSeq.sorted   // sort documentIDs, to ensure consistent input to kmeans
    val assignments = km(stableDocs, k)
    for (i <- 0 until k) { 
      val docsInThisCluster = assignments.view.filter(_._2 == i).map(_._1)  // document IDs assigned to cluster i, lazily produced
      if (docsInThisCluster.size > 0)
        node.children += new DocTreeNode(Set(docsInThisCluster:_*))
    }
    
    if (node.children.size == 1)    // if all docs went into single node, make this a leaf, we are done
      node.children.clear           // (either "really" only one cluster, or clustering alg problem, but let's never infinite loop)
  }
  
  def makeALeafForEachDoc(node:DocTreeNode) = {
    if (node.docs.size > 1)
      node.docs foreach { id => 
        node.children += new DocTreeNode(Set(id))
      }
  }
}


// Use k-means until nodes get small enough, then switch to Connected Components down to leaves
class HybridDocTreeBuilder(protected val docVecs: DocumentSetVectors) {
  
  // Parameters
  val largeNodeSize = 200     // less docs than this, we generate children by finding connected components
  val largeNodeArity = 5      // if we're not finding connected components, we use k-means to split into this many kids
  
  private val km = new KMeansNodeSplitter(docVecs, largeNodeArity)
  private val cc = new ConnectedComponentDocTreeBuilder(docVecs)
  
  private def splitKMeans(node:DocTreeNode, progAbort:ProgressAbortFn) : Unit = {

    if (!progAbort(Progress(0, ClusteringLevel(1)))) { // if we haven't been cancelled...
      
      km.splitNode(node)
  
      // recurse, computing progress along the way
      var i=0
      var denom = node.children.size.toDouble
      node.children foreach { node =>
        splitNode(node, makeNestedProgress(progAbort, i/denom, (i+1)/denom))
        i+=1
      }
      
      progAbort(Progress(1, ClusteringLevel(1)))
    }
  }
  
  
  // here we do not recurse as the connected component splitter always goes down to the leaves
  private def splitCC(node:DocTreeNode, progAbort:ProgressAbortFn) : Unit = {
    val threshSteps = List(1, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1, 0)
    val tree = cc.buildNodeSubtree(node, threshSteps, progAbort) // actually build the tree!
  }
  
  private def splitNode(node:DocTreeNode, progAbort:ProgressAbortFn) : Unit = {
    if (node.docs.size >= largeNodeSize) {
      splitKMeans(node, progAbort)
      if (node.children.isEmpty)
        splitCC(node, progAbort)    // fall back to splitting w/ connected components if k-means can't split it
    } else {
      //println("---- splitting CC with " +  node.docs.size + " items ----")
      splitCC(node, progAbort)
    }
  }
  
  
  def BuildTree(root:DocTreeNode,progAbort: ProgressAbortFn = NoProgressReporting): DocTreeNode = {
    
    splitNode(root, progAbort)
    
    root
  } 
  
}