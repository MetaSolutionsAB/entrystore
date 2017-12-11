# EntryStore Email Template Generator

Follow the following steps to generate customized email templates:

  1. Create a configuration file that suits your needs, you can base it on one of the existing files.
  2. Execute `node ./generate.js ./your-config.json`
  3. Copy the generated files from `./out` to a destination where EntryStore can read from
  4. Change the EntryStore configuration to point out the new templates
