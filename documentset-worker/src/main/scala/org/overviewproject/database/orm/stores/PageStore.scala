package org.overviewproject.database.orm.stores

import org.overviewproject.postgres.SquerylEntrypoint._
import org.overviewproject.tree.orm.stores.BaseStore
import org.overviewproject.database.orm.Schema.{ documents, pages }
import org.squeryl.Query
import org.overviewproject.tree.orm.Page

object PageStore extends BaseStore(pages) {

  def removeReferenceByFile(fileIds: Seq[Long]): Unit = {
    val pagesToUpdate = from(pages)(p =>
      where(p.fileId in fileIds)
        select (p)
        orderBy (p.id)).forUpdate

    pages.update(pagesToUpdate.map(p => p.copy(referenceCount = p.referenceCount - 1)))

    val pagesToDelete = from(pages)(p =>
      where(p.fileId in fileIds and p.referenceCount === 0)
        select (p))

    pages.delete(pagesToDelete)
  }
}