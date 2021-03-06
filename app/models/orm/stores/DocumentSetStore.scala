package models.orm.stores

import org.squeryl.{ KeyedEntityDef, Query }

import org.overviewproject.tree.orm._
import org.overviewproject.tree.orm.stores.BaseStore
import org.overviewproject.tree.orm.finders.FinderResult

import models.orm.finders._
import models.orm.stores._
import models.orm.Schema

object DocumentSetStore extends BaseStore(models.orm.Schema.documentSets) {
  override def delete(query: Query[DocumentSet]): Int = {
    throw new AssertionError("Use DocumentSet.deleteOrCancelJob(), not delete()")
  }

  override def delete[K](k: K)(implicit ked: KeyedEntityDef[DocumentSet, K]): Unit = {
    throw new AssertionError("Use DocumentSet.deleteOrCancelJob(), not delete()")
  }

  def deleteOrCancelJob(query: Query[DocumentSet]): Unit = {
    FinderResult(query).foreach(deleteOrCancelJob(_))
  }


  /**
   * Deletes a DocumentSet, or tells the worker to do so.
   *
   * This method has many side-effects: every database object related to the
   * DocumentSet will be deleted. Every clone job will be deleted (but clones
   * will remain valid, if their jobs are already complete).
   *
   * This method is slow.
   *
   * TODO: Make the worker and only the worker create and delete document
   * sets ... then make this method a one-liner.
   */
  def deleteOrCancelJob(documentSet: Long): Unit = {
    deleteOrCancelPendingClones(documentSet)
    
    DocumentSetCreationJobFinder.byDocumentSet(documentSet).forUpdate.headOption match {
      case Some(job) =>
        if (job.state == DocumentSetCreationJobState.InProgress || job.state == DocumentSetCreationJobState.Cancelled) {
          DocumentSetCreationJobStore.cancel(job)
        } else if (job.state != DocumentSetCreationJobState.Preparing) { // Preparing state handled by worker
          BaseStore(Schema.documentSetCreationJobTrees).delete(DocumentSetCreationJobTreeFinder.byJob(job.id).toQuery)
          DocumentSetCreationJobStore.deletePending(job)
        }
      case None =>
    }
  }
  
  def cancelReclusteringJob(documentSetId: Long, job: DocumentSetCreationJob): Unit = {
    deleteOrCancelPendingClones(documentSetId)
    DocumentSetCreationJobStore.cancel(job)
  }
  
  /**
   * Set the deleted flag to true
   */
  def markDeleted(documentSet: DocumentSet): Unit = {
    Schema.documentSets.insertOrUpdate(documentSet.copy(deleted = true))
  }

  /**
   * Calls deleteOrCancel() on all DocumentSets that are clones of the given
   * DocumentSet which have NotStarted or InProgress jobs.
   */
  private def deleteOrCancelPendingClones(documentSet: Long): Unit = {
    val jobs = FinderResult(
      DocumentSetCreationJobFinder
        .bySourceDocumentSet(documentSet)
        .byState(DocumentSetCreationJobState.NotStarted, DocumentSetCreationJobState.InProgress)
        .forUpdate)
    jobs.foreach((dscj: DocumentSetCreationJob) => deleteOrCancelJob(dscj.documentSetId))
  }

  private def deleteDocumentSetAfterMostReferencesAreDeleted(documentSet: Long): Unit = {
    import org.overviewproject.postgres.SquerylEntrypoint._

    // There's a foreign key from UploadedFile to DocumentSet.
    // Delete the DocumentSet, then any UploadedFile that it refers to.
    val uploadedFiles: Iterable[UploadedFile] = UploadedFileFinder.byDocumentSet(documentSet).toIterable
    Schema.documentSets.delete(documentSet)
    uploadedFiles.map((uf: UploadedFile) => UploadedFileStore.delete(uf.id))
  }
}
