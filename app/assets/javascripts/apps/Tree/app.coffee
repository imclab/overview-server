define [
  'jquery'
  './models/AnimatedFocus'
  './models/animator'
  './models/property_interpolator'
  './models/world'
  './models/DocumentListParams'
  './controllers/keyboard_controller'
  './controllers/logger'
  './controllers/tag_list_controller'
  './controllers/search_result_list_controller'
  './controllers/focus_controller'
  './controllers/tree_controller'
  './controllers/document_list_controller'
  './controllers/document_contents_controller'
  './controllers/TourController'
  './views/Mode'
], ($, \
    AnimatedFocus, Animator, PropertyInterpolator, World, DocumentListParams, \
    KeyboardController, Logger, \
    tag_list_controller, search_result_list_controller, \
    focus_controller, tree_controller, document_list_controller, document_contents_controller, \
    TourController, \
    ModeView) ->

  class App
    constructor: (options) ->
      throw 'need options.mainEl' if !options.mainEl
      throw 'need options.navEl' if !options.navEl

      # TODO remove searchDisabled entirely
      searchDisabled = $(options.mainEl).attr('data-is-searchable') == 'false'
      tourEnabled = $(options.mainEl).attr('data-tooltips-enabled') == 'true'

      els = (->
        html = """
          <div id="tree-app-left">
            <div id="tree-app-tree-container">
              <div id="tree-app-left-top">
                <div id="tree-app-search"></div>
              </div>
              <div id="tree-app-left-middle">
                <div id="tree-app-tree"></div>
                <div id="tree-app-zoom-slider"></div>
              </div>
              <div id="tree-app-left-bottom">
                <div id="tree-app-tags"></div>
              </div>
            </div>
          </div>
          <div id="tree-app-right">
            <div id="tree-app-document-list"></div>
            <div id="tree-app-document-cursor"></div>
          </div>
        """

        $(options.mainEl).html(html)

        if searchDisabled
          $('#tree-app-search').remove()
          $('#tree-app-left').addClass('search-disabled')

        el = (id) -> document.getElementById(id)

        {
          main: options.mainEl
          tree: el('tree-app-tree')
          zoomSlider: el('tree-app-zoom-slider')
          tags: el('tree-app-tags')
          search: el('tree-app-search')
          documentList: el('tree-app-document-list')
          documentCursor: el('tree-app-document-cursor')
          document: el('tree-app-document')
          left: el('tree-app-left')
          leftMiddle: el('tree-app-left-middle')
          leftBottom: el('tree-app-left-bottom')
        }
      )()

      world = new World()

      world.cache.load_root().done ->
        world.state.setDocumentListParams(DocumentListParams.byNodeId(world.cache.on_demand_tree.id_tree.root))
        Logger.set_server(world.cache.server)

      refreshHeight = () ->
        # Make the main div go below the (variable-height) navbar
        h = $(options.navEl).outerHeight()
        $(els.main).css({ top: h })

        # Give room to the tags and search results at the bottom
        h = $(els.leftBottom).outerHeight()
        $(els.leftMiddle).css({ bottom: h })

        # Round the iframe's parent's width, because it needs an integer number of px
        $document = $(els.document)
        $document.find('iframe')
          .width(1)
          .width($document.width())

      refocus = ->
        # Pull focus out of the iframe.
        #
        # We can't listen for events on the document iframe; so if it's present,
        # it breaks keyboard shortcuts. We need to re-grab focus whenever we can
        # without disturbing the user.
        #
        # For instance, if the user is logging in to DocumentCloud in the iframe,
        # we don't want to steal focus; so a timer is bad, and a mousemove handler
        # is bad. But if we register a click, it's worth using that to steal focus.
        window.focus() if document.activeElement?.tagName == 'IFRAME'

      refocus_body_on_leave_window = ->
        # Ugly fix for https://github.com/overview/overview-server/issues/321
        hidden = undefined

        callback = (e) ->
          if !document[hidden]
            refocus()

        if document[hidden]?
          document.addEventListener("visibilitychange", callback)
        else if document[hidden = "mozHidden"]?
          document.addEventListener("mozvisibilitychange", callback)
        else if document[hidden = "webkitHidden"]?
          document.addEventListener("webkitvisibilitychange", callback)
        else if document[hidden = "msHidden"]?
          document.addEventListener("msvisibilitychange", callback)
        else
          hidden = undefined

      refocus_body_on_event = ->
        # Ugly fix for https://github.com/overview/overview-server/issues/362
        $('body').on 'click', (e) ->
          refocus()

      keyboard_controller = new KeyboardController(document)

      interpolator = new PropertyInterpolator(500, (x) -> -Math.cos(x * Math.PI) / 2 + 0.5)
      animator = new Animator(interpolator)
      focus = new AnimatedFocus({}, { animator: animator })
      focus_controller(els.zoomSlider, focus)

      controller = tree_controller(els.tree, world.cache, focus, world.state)
      keyboard_controller.add_controller('TreeController', controller)

      controller = document_contents_controller({
        cache: world.cache
        state: world.state
        el: els.document
      })
      keyboard_controller.add_controller('DocumentContentsController', controller)

      controller = document_list_controller(els.documentList, els.documentCursor, world.cache, world.state)
      keyboard_controller.add_controller('DocumentListController', controller)

      new ModeView({ el: options.mainEl, state: world.state })

      tag_list_controller({
        cache: world.cache
        state: world.state
        el: els.tags
      })

      if !searchDisabled
        search_result_list_controller({
          cache: world.cache
          state: world.state
          el: els.search
        })

      for store in [ world.cache.tag_store, world.cache.search_result_store ]
        for event in [ 'added', 'removed', 'changed' ]
          store.observe(event, refreshHeight)

      throttledRefreshHeight = _.throttle(refreshHeight, 100)
      $(window).resize(throttledRefreshHeight)
      refreshHeight()

      if tourEnabled
        TourController()

      refocus_body_on_leave_window()
      refocus_body_on_event()
