define [
  'underscore'
  'apps/Tree/models/DocumentListParams'
], (_, DocumentListParams) ->
  CacheFixture1 =
    document_store:
      documents:
        # Out of order, to make sure we sort them
        5: { id: 5, description: 'doc5', tagids: [1], nodeids: [ 1, 2, 5 ] },
        6: { id: 6, description: 'doc6', tagids: [], nodeids: [ 1, 3, 6] },
        7: { id: 7, description: 'doc7', tagids: [1], nodeids: [ 1, 3, 7 ] },
        1: { id: 1, description: 'doc1', tagids: [1], nodeids: [1, 2, 4] },
        2: { id: 2, description: 'doc2', tagids: [1, 2], nodeids: [ 1, 2, 4] },
        3: { id: 3, description: 'doc3', tagids: [], nodeids: [ 1, 2, 4 ] },
        4: { id: 4, description: 'doc4', tagids: [1, 2], nodeids: [ 1, 2, 5 ] },
    on_demand_tree:
      id_tree:
        root: 1
        children:
          1: [2,3]
          2: [4,5]
          3: [6,7]
        parent:
          2: 1
          3: 1
          4: 2
          5: 2
          6: 3
          7: 3
      nodes:
        1: { doclist: { n: 7, docids: [ 1, 2, 3, 4, 5, 6, 7 ] } },
        2: { doclist: { n: 5, docids: [ 1, 2, 3, 4, 5 ] } },
        3: { doclist: { n: 2, docids: [ 6, 7 ] } },
        4: { doclist: { n: 3, docids: [ 1, 2, 3 ] } },
        5: { doclist: { n: 2, docids: [ 4, 5 ] } },
        6: { doclist: { n: 1, docids: [ 6 ] } },
        7: { doclist: { n: 1, docids: [ 7 ] } },
    tag_store:
      tags:
        1: { name: 'tag 1', color: '#111111' }
        2: { name: 'tag 2', color: '#222222' }

  describe 'apps/Tree/models/DocumentListParams', ->
    cache = undefined
    params = undefined

    beforeEach -> cache = JSON.parse(JSON.stringify(CacheFixture1))

    describe 'all', ->
      beforeEach -> params = DocumentListParams.all()

      it 'should have type all', -> expect(params.type).toEqual('all')
      it 'should have no params', -> expect(params.params).toEqual([])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(all)')
      it 'should set JSON empty', -> expect(params.toJSON()).toEqual({})
      it 'should equals() another', -> expect(params.equals(DocumentListParams.all())).toBe(true)
      it 'should not equals() something else', -> expect(params.equals(DocumentListParams.untagged())).toBe(false)

      it 'should find all documents from cache, sorted', ->
        result = params.findDocumentsFromCache(cache)
        expect(x.id for x in result).toEqual([ 1, 2, 3, 4, 5, 6, 7 ])

    describe 'byNodeId', ->
      beforeEach -> params = DocumentListParams.byNodeId(2)

      it 'should have type node', -> expect(params.type).toEqual('node')
      it 'should have one param', -> expect(params.params).toEqual([2])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(node:2)')
      it 'should have a JSON param', -> expect(params.toJSON()).toEqual({ nodes: [2] })
      it 'should have an API param', -> expect(params.toApiParams(nodes: 1)).toEqual({ nodes: '2' })
      it 'should equals() another', -> expect(params.equals(DocumentListParams.byNodeId(2))).toBe(true)
      it 'should not equals() something else', -> expect(params.equals(DocumentListParams.byNodeId(3))).toBe(false)

      it 'should find all documents from cache, sorted', ->
        result = params.findDocumentsFromCache(cache)
        expect(x.id for x in result).toEqual([ 1, 2, 3, 4, 5 ])

    describe 'byTagId', ->
      beforeEach -> params = DocumentListParams.byTagId(1)

      it 'should have type tag', -> expect(params.type).toEqual('tag')
      it 'should have one param', -> expect(params.params).toEqual([1])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(tag:1)')
      it 'should have a JSON param', -> expect(params.toJSON()).toEqual({ tags: [1] })
      it 'should have an API param', -> expect(params.toApiParams(nodes: 1)).toEqual({ nodes: 1, tags: '1' })

      it 'should find all documents from cache, sorted', ->
        result = params.findDocumentsFromCache(cache)
        expect(x.id for x in result).toEqual([1, 2, 4, 5, 7])

    describe 'byDocumentId', ->
      beforeEach -> params = DocumentListParams.byDocumentId(1)

      it 'should have type document', -> expect(params.type).toEqual('document')
      it 'should have one param', -> expect(params.params).toEqual([1])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(document:1)')
      it 'should have a JSON param', -> expect(params.toJSON()).toEqual({ documents: [1] })
      it 'should have an API param', -> expect(params.toApiParams(nodes: 1)).toEqual({ nodes: 1, documents: '1' })

      it 'should find a document when it is in the cache', ->
        result = params.findDocumentsFromCache(cache)
        expect(x.id for x in result).toEqual([1])

      it 'should find an empty list when the document is not in the cache', ->
        result = DocumentListParams.byDocumentId(13231241).findDocumentsFromCache(cache)
        expect(result).toEqual([])

    describe 'intersect', ->
      describe 'node + document when they intersect', ->
        beforeEach ->
          params = DocumentListParams.intersect(
            DocumentListParams.byDocumentId(1),
            DocumentListParams.byNodeId(2)
          )

        it 'should have type intersection', -> expect(params.type).toEqual('intersection')
        it 'should have params', -> expect(params.params).toEqual([['document', [1]], ['node', [2]]])
        it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(document:1) ∩ DocumentListParams(node:2)')
        it 'should have two JSON params', -> expect(params.toJSON()).toEqual({ documents: [1], nodes: [2] })
        it 'should have two API params', -> expect(params.toApiParams(nodes: 1)).toEqual({ documents: '1', nodes: '2' })

        it 'should find the document when it is in the cache', ->
          result = params.findDocumentsFromCache(cache)
          expect(x.id for x in result).toEqual([1])

      describe 'tag + document when they do not intersect', ->
        beforeEach ->
          params = DocumentListParams.intersect(
            DocumentListParams.byDocumentId(1),
            DocumentListParams.byTagId(2)
          )

        it 'should not find any documents when they are not in the cache', ->
          expect(params.findDocumentsFromCache(cache)).toEqual([])

      it 'should not allow two intersecting two lists of the same type', ->
        expect(-> DocumentListParams.intersect(DocumentListParams.byTagId(1), DocumentListParams.byTagId(2)))
          .toThrow()

    describe 'untagged', ->
      beforeEach -> params = DocumentListParams.untagged()

      it 'should have type untagged', -> expect(params.type).toEqual('untagged')
      it 'should have no params', -> expect(params.params).toEqual([])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(untagged)')
      it 'should have a JSON param', -> expect(params.toJSON()).toEqual({ tags: [0] })
      it 'should have an API param', -> expect(params.toApiParams(nodes: 1)).toEqual({ nodes: 1, tags: '0' })

      it 'should find all untagged documents from cache, sorted', ->
        result = params.findDocumentsFromCache(cache)
        expect(x.id for x in result).toEqual([3, 6])

    describe 'bySearchResultId', ->
      beforeEach -> params = DocumentListParams.bySearchResultId(3)

      it 'should have type searchResult', -> expect(params.type).toEqual('searchResult')
      it 'should have one param', -> expect(params.params).toEqual([3])
      it 'should have toString', -> expect(params.toString()).toEqual('DocumentListParams(searchResult:3)')
      it 'should have a JSON param', -> expect(params.toJSON()).toEqual({ searchResults: [3] })
      it 'should have an API param', -> expect(params.toApiParams(nodes: 1)).toEqual({ nodes: 1, searchResults: '3' })

      it 'should find no documents from the cache', ->
        expect(params.findDocumentsFromCache(cache)).toEqual([])
