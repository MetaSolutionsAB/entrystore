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

var Mailgen = require('mailgen');

var mailGenerator = new Mailgen({
    theme: 'salted',
    product: {
        name: conf.productName,
        link: conf.productURL,
        logo: conf.productLogoURL,
	copyright: conf.copyright,
    }
});

var emailSignup = {
    body: {
        name: '__NAME__',
        intro: 'Welcome to ' + conf.productName + '!',
        action: {
            instructions: 'To get started with ' + conf.productName + ', please click here:',
            button: {
                color: '#22BC66', // Optional action button color 
                text: 'Confirm your account',
                link: '__CONFIRMATION_LINK__'
            }
        },
        outro: conf.supportText,
    }
};

fs.writeFileSync(outDir + 'signup.html', mailGenerator.generate(emailSignup), 'utf8');
fs.writeFileSync(outDir + 'signup.txt', mailGenerator.generatePlaintext(emailSignup), 'utf8');

var emailPwReset = {
    body: {
        name: '__NAME__',
        intro: 'You have received this email because a password reset request for your ' + conf.productName + ' account was received.',
        action: {
            instructions: 'Click the button below to reset your password:',
            button: {
                color: '#22BC66', // Optional action button color 
                text: 'Reset your password',
                link: '__CONFIRMATION_LINK__'
            }
        },
        outro: [
            'If you did not request a password reset, no further action is required on your part.',
            conf.supportText,
        ],
    }
};

fs.writeFileSync(outDir + 'pwreset.html', mailGenerator.generate(emailPwReset), 'utf8');
fs.writeFileSync(outDir + 'pwreset.txt', mailGenerator.generatePlaintext(emailPwReset), 'utf8');
