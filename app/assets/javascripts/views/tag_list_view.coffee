observable = require('models/observable').observable

TAG_KEY = 'overview-tag'

class TagListView
  observable(this)

  constructor: (@div, @tag_list) ->
    this._init_html()
    this._observe_tag_add()
    this._observe_tag_remove()

  _init_html: () ->
    $div = $(@div)
    $ul = $('<ul></ul>')
    $form_li = $('<li></li>')
    $form_li.append(this._create_form())
    $ul.append($form_li)
    $div.append($ul)

    notify = this._notify.bind(this)

    $ul.on 'click', 'a.tag-add', (e) ->
      e.preventDefault()
      $li = $(this).closest('li')
      tag = $li.data(TAG_KEY)
      notify('add-clicked', tag)

    $ul.on 'click', 'a.tag-remove', (e) ->
      e.preventDefault()
      $li = $(this).closest('li')
      tag = $li.data(TAG_KEY)
      notify('remove-clicked', tag)

  _create_form: () ->
    $form = $('<form method="post" action="#"><div class="input-append"><input type="text" name="tag_name" size="10" placeholder="New tag" /><input type="submit" value="Tag" class="btn" /></div></form')
    $form.on 'submit', (e) =>
      e.preventDefault()
      name = $.trim($form.find('input[name=tag_name]').val())
      if name.length > 0
        this._notify('create-submitted', { name: name })

  _observe_tag_add: () ->
    @tag_list.observe 'tag-added', (obj) =>
      this._add_tag(obj.position, obj.tag)

  _add_tag: (position, tag) ->
    $li = $('<li><div class="btn-group"><a class="btn tag-name"></a><a class="btn tag-add"><i class="icon-plus" alt="add tag to selection"></i></a><a class="btn tag-remove" alt="remove tag from selection"><i class="icon-minus"></i></a></div></li>')
    $li.data(TAG_KEY, tag)
    $li.find('.tag-name').text(tag.name)

    $ul = $('ul', @div)
    $li.insertBefore($ul.children()[position])

  _observe_tag_remove: () ->
    @tag_list.observe 'tag-removed', (obj) =>
      this._remove_tag(obj.position)

  _remove_tag: (position) ->
    $ul = $('ul', @div)
    $li = $($ul.children()[position])
    $li.remove()

exports = require.make_export_object('views/tag_list_view')
exports.TagListView = TagListView
