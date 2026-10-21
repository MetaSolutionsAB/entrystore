# EntryStore Email Template Generator

Follow the following steps to generate customized email templates:

  1. Create a configuration file that suits your needs, you can base it on one of the existing files.
  2. Execute `node ./generate.js ./your-config.json`
  3. Copy the generated files from `./out` to a destination where EntryStore can read from
  4. Change the EntryStore configuration to point out the new templates

The sign-up template holds two mails. EntryStore sends the part between `<!--BEGIN new-account-->` and
`<!--END new-account-->` as the confirmation, and the part between `<!--BEGIN existing-account-->` and
`<!--END existing-account-->` as the notice for a sign-up with the address of an existing account; everything
outside the two sections is in both. The generator writes both sections. When editing a template by hand, wrap the
confirmation part, including its link, in `new-account` and add an `existing-account` section. EntryStore rejects a
template whose markers are unpaired, nested, repeated or name another section, that has any other comment naming
`new-account` or `existing-account` (such as a misspelled marker), that has an `existing-account` section but no
`new-account` section, or whose notice would contain `__CONFIRMATION_LINK__`. It sends no notice from a template
without an `existing-account` section. Templates with sections need
EntryStore 6.2 or later, as earlier versions send both parts in every sign-up mail.
