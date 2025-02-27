# Gerrit Code Review code-owners plugin

This plugin provides support for defining code owners for files in a repository.

If the code-owners plugin is enabled, changes can only be submitted if all
touched files are covered by approvals from code owners.

For a detailed description of the plugin functionality please refer to the
[plugin documentation](https://android-review.googlesource.com/plugins/code-owners/Documentation/index.html).

IMPORTANT: Before installing/enabling the plugin follow the instructions from
the [setup guide](https://android-review.googlesource.com/plugins/code-owners/Documentation/setup-guide.html).

NOTE: The plugin documentation only renders correctly when the plugin is
installed in Gerrit and the documentation is accessed via
https://<gerrit-host>/plugins/code-owners/Documentation/index.html. If you want
to read the documentation before installing the plugin, you can find it properly
rendered
[here](https://android-review.googlesource.com/plugins/code-owners/Documentation/index.html).

## JavaScript Plugin

For testing the plugin with
[Gerrit FE Dev Helper](https://gerrit.googlesource.com/gerrit-fe-dev-helper/)
build the JavaScript bundle and copy it to the `plugins/` folder:

    bazel build //plugins/code-owners/ui:code-owners
    cp -f bazel-bin/plugins/code-owners/ui/code-owners.js plugins/

and let the Dev Helper redirect from
`.+/plugins/code-owners/static/code-owners.js` to
`http://localhost:8081/plugins_/code-owners.js`.
