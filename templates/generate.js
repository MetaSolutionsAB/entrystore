#!/usr/bin/env node

if (process.argv.length > 2) {
    var confPath = process.argv[2];
}

if (confPath == null) {
    console.log("No configuration file specified. Aborting.");
    return;
}

var conf = require(confPath);

var outDir = './out/';

var fs = require('fs');
if (!fs.existsSync(outDir)){
    fs.mkdirSync(outDir);
}

var path = require('path');
var Mailgen = require('mailgen');
var htmlMinify = require('html-minifier').minify;

var minify = function(html) {
    return htmlMinify(html, {removeComments: true, collapseWhitespace: true, minifyCSS: true, keepClosingSlash: true, caseSensitive: true});
};

var splitBody = function(html) {
  var open = html.indexOf('<body');
  var openEnd = html.indexOf('>', open) + 1;
  var close = html.lastIndexOf('</body>');
  if (open < 0 || openEnd <= 0 || close < openEnd) {
    throw new Error('Generated mail has no <body> element');
  }
  return {prefix: html.slice(0, openEnd), body: html.slice(openEnd, close), suffix: html.slice(close)};
};

// One sign-up template holding both mails, each <body> content in its own section, so rendering a section, as
// EntryStore does, gives back exactly that mail. The markers are added after minification, which strips comments.
var signupTemplate = function(newAccountHtml, existingAccountHtml) {
  var newAccount = splitBody(newAccountHtml);
  var existingAccount = splitBody(existingAccountHtml);
  if (newAccount.prefix !== existingAccount.prefix || newAccount.suffix !== existingAccount.suffix) {
    throw new Error('The two sign-up mails differ outside <body>, so they cannot share one template');
  }
  if (newAccount.body.indexOf('<!--') >= 0 || existingAccount.body.indexOf('<!--') >= 0) {
    throw new Error('A sign-up mail contains a comment, which EntryStore could read as a section marker');
  }
  return newAccount.prefix
      + '<!--BEGIN new-account-->' + newAccount.body + '<!--END new-account-->'
      + '<!--BEGIN existing-account-->' + existingAccount.body + '<!--END existing-account-->'
      + newAccount.suffix;
};

for (var lang in conf) {
  if (!conf.hasOwnProperty(lang)) {
      continue;
  }

  var langStr = conf[lang];

  // A missing string would silently render as an empty paragraph in the account-exists notice.
  ['signupExistingIntro', 'signupExistingInstructions', 'signupExistingOutro'].forEach(function(key) {
    if (!langStr[key]) {
      throw new Error('Language ' + lang + ' has no ' + key + '; add it before generating the sign-up template');
    }
  });

  var mailGenerator = new Mailgen({
    /*
    theme: {
        path: path.resolve('theme/index.html'),
        plaintextPath: path.resolve('theme/index.txt')
    },
    */
    theme: 'salted',
    product: {
      name: langStr.productName,
      link: langStr.productURL,
      logo: langStr.productLogoURL,
      copyright: langStr.copyright
    }
  });

  var emailSignup = {
    body: {
      greeting: langStr.greeting,
      name: '__NAME__',
      intro: langStr.signupIntro,
      action: {
        instructions: langStr.signupInstructions,
        button: {
          color: '#22BC66', // Optional action button color
          text: langStr.signupButton,
          link: '__CONFIRMATION_LINK__'
        }
      },
      outro: langStr.supportText
    }
  };

  // Sent instead of the confirmation when the address already has an account; it carries no link.
  var emailSignupExistingAccount = {
    body: {
      greeting: langStr.greeting,
      name: '__NAME__',
      intro: [
        langStr.signupExistingIntro,
        langStr.signupExistingInstructions
      ],
      outro: [
        langStr.signupExistingOutro,
        langStr.supportText
      ]
    }
  };

  var emailPwReset = {
    body: {
      greeting: langStr.greeting,
      intro: langStr.pwresetIntro,
      action: {
        instructions: langStr.pwresetInstructions,
        button: {
          color: '#22BC66', // Optional action button color
          text: langStr.pwresetButton,
          link: '__CONFIRMATION_LINK__'
        }
      },
      outro: [
        langStr.pwresetOutro,
        langStr.supportText
      ]
    }
  };

  var emailPwChange = {
    body: {
      greeting: langStr.greeting,
      name: '__NAME__',
      intro: langStr.pwchangeIntro,
      outro: langStr.supportText
    }
  };

  /*if (lang == 'en') {
    lang = '';
  } else {
    lang = '_' + lang;
  }*/
  lang = '_' + lang;

  fs.writeFileSync(outDir + 'email_signup' + lang + '.html', signupTemplate(
      minify(mailGenerator.generate(emailSignup)),
      minify(mailGenerator.generate(emailSignupExistingAccount))), 'utf8');
  fs.writeFileSync(outDir + 'email_pwreset' + lang + '.html', minify(mailGenerator.generate(emailPwReset)), 'utf8');
  fs.writeFileSync(outDir + 'email_pwchange' + lang + '.html', minify(mailGenerator.generate(emailPwChange)), 'utf8');

  //fs.writeFileSync(outDir + 'email_signup' + lang + '.txt', mailGenerator.generatePlaintext(...), 'utf8');
}
