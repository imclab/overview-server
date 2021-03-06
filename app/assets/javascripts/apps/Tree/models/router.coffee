define ->
  class Router
    constructor: (start_path=undefined) ->
      # TODO: test, etc
      @start_path = start_path || '' + window.document.location
      if match = /(\d+)\/trees\/(\d+)[^\d]*$/.exec(@start_path)
        @document_set_id = +match[1]
        @tree_id = +match[2]

    route_to_path: (route, id=undefined) ->
      switch (route)
        when 'root' then this._root_path()
        when 'node' then this._node_path(id)
        when 'node_update' then this._node_path(id)
        when 'documents' then this._documents_path()
        when 'document_view' then this._document_view_path(id)
        when 'create_log_entries' then this._create_log_entries_path()
        when 'tag_base' then this._tag_base()
        when 'tag_add' then this._tag_add_path(id)
        when 'tag_remove' then this._tag_remove_path(id)
        when 'tag_node_counts' then this._tag_node_counts_path(id)
        when 'untagged_node_counts' then this._untagged_node_counts()
        when 'searches_base' then this._searches_base()
        when 'search_result_base' then this._search_result_base()
        when 'search_result_node_counts' then this._search_result_node_counts_path(id)

    _document_view_path: (id) ->
      "/documents/#{id}"

    _root_path: () ->
      "/trees/#{@tree_id}/nodes"

    _node_path: (id) ->
      throw new Error("Must have positive id") if id <= 0
      "/trees/#{@tree_id}/nodes/#{id}"

    _documents_path: () ->
      "/documentsets/#{@document_set_id}/documents"

    _create_log_entries_path: () ->
      "/documentsets/#{@document_set_id}/log-entries/create-many"

    _tag_base: () ->
      "/documentsets/#{@document_set_id}/tags"

    _tag_add_path: (id) ->
      throw new Error("Must have positive id") if id <= 0
      "/documentsets/#{@document_set_id}/tags/#{id}/add"

    _tag_remove_path: (id) ->
      throw new Error("Must have positive id") if id <= 0
      "/documentsets/#{@document_set_id}/tags/#{id}/remove"

    _tag_node_counts_path: (id) ->
      throw new Error("Must have positive id") if id <= 0
      "/documentsets/#{@document_set_id}/tags/#{id}/node-counts"

    _untagged_node_counts: () ->
      "/trees/#{@tree_id}/tags/untagged-node-counts"

    _searches_base: () ->
      "/documentsets/#{@document_set_id}/searches"

    _search_result_base: () ->
      "/documentsets/#{@document_set_id}/search-results"

    _search_result_node_counts_path: (id) ->
      throw new Error("Must have positive id") if id <= 0
      "/documentsets/#{@document_set_id}/search-results/#{id}/node-counts"
