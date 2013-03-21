define [ './observable' ], (observable) ->
  class RemoteTagList
    observable(this)

    constructor: (@cache) ->
      @tag_store = @cache.tag_store
      @document_store = @cache.document_store
      @tags = @tag_store.tags

      @tag_store.observe('tag-added', (v) => this._notify('tag-added', v))
      @tag_store.observe('tag-removed', (v) => this._notify('tag-removed', v))
      @tag_store.observe('tag-id-changed', (old_tagid, tag) => this._notify('tag-id-changed', old_tagid, tag))
      @tag_store.observe('tag-changed', (v) => this._notify('tag-changed', v))

    # FIXME remove these
    create_tag: (name) ->
      new_tag = @cache.add_tag(name)
      @cache.create_tag(new_tag)
      new_tag

    edit_tag: (tag, new_tag) -> @cache.update_tag(tag, new_tag)
    delete_tag: (tag) -> @cache.delete_tag(tag)

    add_tag_to_selection: (tag, selection) ->
      documents = this._selection_to_documents(selection)
      return if !documents?

      this._maybe_add_tagid_to_document(tag.id, document) for document in documents

      @cache.on_demand_tree.id_tree.edit =>
        if tag.doclist?
          @document_store.remove_doclist(tag.doclist)
          @tag_store.change(tag, { doclist: undefined })

        if selection.allows_correct_tagcount_adjustments()
          @cache.on_demand_tree.add_tag_to_node(nodeid, tag) for nodeid in selection.nodes

      selection_post_data = this._selection_to_post_data(selection)
      @cache.transaction_queue.queue =>
        deferred = @cache.server.post('tag_add', selection_post_data, { path_argument: tag.id })
        deferred.done(this._after_tag_add_or_remove.bind(this, tag))
        if !selection.allows_correct_tagcount_adjustments()
          deferred.done(=> @cache.refresh_tagcounts(tag))
        deferred

    remove_tag_from_selection: (tag, selection) ->
      documents = this._selection_to_documents(selection)
      return if !documents?

      this._maybe_remove_tagid_from_document(tag.id, document) for document in documents

      @cache.on_demand_tree.id_tree.edit =>
        if tag.doclist?
          @document_store.remove_doclist(tag.doclist)
          @tag_store.change(tag, { doclist: undefined })

        if selection.allows_correct_tagcount_adjustments()
          @cache.on_demand_tree.remove_tag_from_node(nodeid, tag) for nodeid in selection.nodes

      selection_post_data = this._selection_to_post_data(selection)
      @cache.transaction_queue.queue =>
        deferred = @cache.server.post('tag_remove', selection_post_data, { path_argument: tag.id })
        deferred.done(this._after_tag_add_or_remove.bind(this, tag))
        if !selection.allows_correct_tagcount_adjustments()
          deferred.done(=> @cache.refresh_tagcounts(tag))
        deferred

    _after_tag_add_or_remove: (tag, obj) ->
      if obj.tag?.doclist? && obj.documents?
        documents = {}
        documents[doc.id] = doc for doc in obj.documents
        @document_store.add_doclist(obj.tag.doclist, documents)
      @tag_store.change(tag, obj.tag)

    _selection_to_post_data: (selection) ->
      {
        nodes: selection.nodes.join(','),
        documents: selection.documents.join(','),
        tags: selection.tags.join(','),
      }

    # Returns loaded docids from selection.
    #
    # If nothing is selected, returns undefined. Do not confuse this with
    # the empty-Array return value, which only means we don't have any loaded
    # docids that match the selection.
    _selection_to_documents: (selection) ->
      if !selection.nodes.length && !selection.tags.length && !selection.documents.length
        return undefined

      selection.documents_from_cache(@cache)

    _maybe_add_tagid_to_document: (tagid, document) ->
      tagids = document.tagids
      if tagid not in tagids
        tagids.push(tagid)
        @document_store.change(document)

    _maybe_remove_tagid_from_document: (tagid, document) ->
      tagids = document.tagids
      index = tagids.indexOf(tagid)
      if index >= 0
        tagids.splice(index, 1)
        @document_store.change(document)

    find_tag_by_name: (name) ->
      @tag_store.find_tag_by_name(name)

    find_tag_by_id: (id) ->
      @tag_store.find_tag_by_id(id)