define [
  'jquery'
  './document_store'
  './on_demand_tree'
  './tag_store'
  './TagLikeApi'
  './search_result_store'
  './server'
  './needs_resolver'
  './transaction_queue'
  'i18n'
], ($, DocumentStore, OnDemandTree, TagStore, TagLikeApi, SearchResultStore, Server, NeedsResolver, TransactionQueue, i18n) ->
  t = i18n.namespaced('views.Tree.show.cache')

  Deferred = $.Deferred

  # A Cache stores documents, nodes and tags, plus a transaction queue.
  #
  # Nodes, documents and tags are plain old data objects, stored in
  # @document_store, @tag_store and @on_demand_tree.
  #
  # @transaction_queue can be used to fetch data from the server and
  # send modifications to the server.
  #
  # * add_*, edit_*, remove_*: Add or remove objects from their appropriate
  #   stores. (The methods are here when removals cross boundaries: for example,
  #   removing a tag means all documents must be modified.)
  # * create_*, update_*, delete_*: Update the cache, *and* queue a transaction to
  #   modify the server's data.
  #
  # Lots of these methods are inconsistent and ugly. This whole class needs
  # rethinking and splitting-up.
  class Cache
    constructor: () ->
      @server = new Server
      @router = @server.router
      @document_store = new DocumentStore()
      @tag_store = new TagStore()
      @search_result_store = new SearchResultStore(@router.route_to_path("search_result_base"))
      @needs_resolver = new NeedsResolver(@tag_store, @search_result_store, @server)
      @transaction_queue = new TransactionQueue()
      @tag_api = new TagLikeApi(@tag_store, @transaction_queue, @router.route_to_path("tag_base"))
      @search_result_api = new TagLikeApi(@search_result_store, @transaction_queue, @router.route_to_path("searches_base"))
      @on_demand_tree = new OnDemandTree(this) # FIXME this is ugly

    load_root: () ->
      @on_demand_tree.demand_root()

    # Serializes calls to NeedsResolver.get_deferred() through @transaction_queue.
    #
    # FIXME rewrite NeedsResolver & co. This is all very confusing.
    resolve_deferred: () ->
      args = Array.prototype.slice.call(arguments, 0)
      resolver = @needs_resolver

      deferred = new Deferred()

      @transaction_queue.queue ->
        inner_deferred = resolver.get_deferred.apply(resolver, args)
        inner_deferred.done ->
          inner_args = Array.prototype.slice.call(arguments, 0)
          deferred.resolve.apply(deferred, inner_args)
        inner_deferred.fail ->
          inner_args = Array.prototype.slice.call(arguments, 0)
          deferred.reject.apply(deferred, inner_args)

      deferred

    # Requests new node counts from the server, and updates the cache
    #
    # Params:
    #
    # * tag: tag (or tag ID) to refresh.
    # * onlyNodeIds: if set, only refresh a few node IDs. Otherwise, refresh
    #   every loaded node ID.
    refresh_tagcounts: (tag, onlyNodeIds=undefined) ->
      # the ID might change before the transaction begins
      tag = @tag_store.find_by_id(tag) if !tag.id?

      @transaction_queue.queue(=>
        tagId = tag.id # Now the ID is correct
        @_refresh_post('tagCounts', 'tag_node_counts', tagId, onlyNodeIds)
      , 'refresh_tagcounts')

    refresh_untagged: (onlyNodeIds=undefined) ->
      @transaction_queue.queue(=>
        @_refresh_post('tagCounts', 'untagged_node_counts', 0, onlyNodeIds)
      , 'refresh_untagged')

    _refresh_post: (countsKey, endpoint, pathArgument, onlyNodeIds) ->
      nodes = @on_demand_tree.nodes

      node_ids = if onlyNodeIds?
        onlyNodeIds
      else
        _(nodes).keys()

      @server.post(endpoint, {nodes: node_ids.join(',')}, {path_argument: pathArgument})
        .done (data) =>
          i = 0
          while i < data.length
            nodeid = data[i++]
            count = data[i++]

            node = nodes[nodeid]

            if node?
              counts = (node[countsKey] ||= {})

              if count
                counts[pathArgument] = count
              else
                delete counts[pathArgument]

          @on_demand_tree.id_tree.batchAdd(->) # trigger update

          undefined

    refreshSearchResultCounts: (searchResult, onlyNodeIds=undefined) ->
      # The ID might change before the transaction begins
      searchResult = @search_result_store.find_by_id(searchResult) if !searchResult.id?

      @transaction_queue.queue(=>
        searchResultId = searchResult.id # now the ID is correct
        if searchResultId < 0
          $.Deferred().resolve() # when it gets set, we'll refresh again
        else
          @_refresh_post('searchResultCounts', 'search_result_node_counts', searchResultId, onlyNodeIds)
      , 'refreshSearchResultCounts')

    create_tag: (tag, options) ->
      @tag_api.create(tag, options)

    add_tag: (attributes) ->
      @tag_store.add(attributes)

    edit_tag: (tag, new_tag) ->
      @tag_store.change(tag, new_tag)

    update_tag: (tag, new_tag) ->
      this.edit_tag(tag, new_tag)
      @tag_api.update(tag, new_tag)

    remove_tag: (tag) ->
      tagId = tag.id

      @document_store.remove_tag_id(tagId)

      nodes = @on_demand_tree.nodes
      for __, node of nodes
        tagCounts = node.tagCounts
        if tagCounts? && tagId of tagCounts
          delete tagCounts[tagId]

      @on_demand_tree.id_tree.batchAdd(->) # trigger callbacks

      @tag_store.remove(tag)

    delete_tag: (tag) ->
      @tag_api.destroy(tag)
        .done => @remove_tag(tag)

    edit_node: (node, new_node) ->
      @on_demand_tree.id_tree.batchAdd -> # trigger callbacks
        for k, v of new_node
          if !v?
            node[k] = undefined
          else
            node[k] = JSON.parse(JSON.stringify(v))

    update_node: (node, new_node) ->
      id = node.id

      this.edit_node(node, new_node)

      @transaction_queue.queue =>
        @server.post('node_update', new_node, { path_argument: id })

    # Adds the given Tag to all documents specified by the DocumentList.
    #
    # This only applies to documents in our document_store. The server-side
    # data will remain unchanged.
    addTagToDocumentListLocal: (tag, documentList) ->
      for document in documentList.findDocumentsFromCache(@)
        @_maybe_add_tagid_to_document(tag.id, document)
      undefined

    # Tells the server to add the given Tag to all documents specified by the
    # DocumentList.
    #
    # This leaves our document_store unaffected.
    addTagToDocumentListRemote: (tag, documentList) ->
      @transaction_queue.queue(=>
        postData = documentList.toApiParams(nodes: @on_demand_tree.id_tree.root)
        @server.post('tag_add', postData, { path_argument: tag.id })
      , 'Cache.addTagToDocumentListRemote')

    # Adds the given Tag to all documents specified by the DocumentList.
    #
    # This calls addTagToDocumentListLocal() and addTagToDocumentListRemote().
    #
    # Return value: a Deferred which will be resolved once the tag has been
    # added.
    addTagToDocumentList: (tag, documentList) ->
      @addTagToDocumentListLocal(tag, documentList)
      @addTagToDocumentListRemote(tag, documentList)

    # Removes the given Tag from all documents in the DocumentList.
    #
    # This only applies to documents in our document_store. The server-side
    # data will remain unchanged.
    removeTagFromDocumentListLocal: (tag, documentList) ->
      for document in documentList.findDocumentsFromCache(@)
        @_maybe_remove_tagid_from_document(tag.id, document)
      undefined

    # Tells the server to remove the given Tag to all documents specified by the
    # DocumentList.
    #
    # This leaves our document_store unaffected.
    removeTagFromDocumentListRemote: (tag, documentList) ->
      @transaction_queue.queue =>
        postData = documentList.toApiParams(nodes: @on_demand_tree.id_tree.root)
        @server.post('tag_remove', postData, { path_argument: tag.id })

    # Removes the given Tag to all documents specified by the DocumentList.
    #
    # This calls removeTagFromDocumentListLocal() and
    # removeTagFromDocumentListRemote().
    #
    # Return value: a Deferred which will be resolved once the tag has been
    # removed.
    removeTagFromDocumentList: (tag, documentList) ->
      @removeTagFromDocumentListLocal(tag, documentList)
      @removeTagFromDocumentListRemote(tag, documentList)

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
