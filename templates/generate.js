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

for (var lang in conf) {
  if (!conf.hasOwnProperty(lang)) {
      continue;
  }

  var langStr = conf[lang];

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

  fs.writeFileSync(outDir + 'email_signup' + lang + '.html', minify(mailGenerator.generate(emailSignup)), 'utf8');
  fs.writeFileSync(outDir + 'email_pwreset' + lang + '.html', minify(mailGenerator.generate(emailPwReset)), 'utf8');
  fs.writeFileSync(outDir + 'email_pwchange' + lang + '.html', minify(mailGenerator.generate(emailPwChange)), 'utf8');

  //fs.writeFileSync(outDir + 'email_signup' + lang + '.txt', mailGenerator.generatePlaintext(...), 'utf8');
}
