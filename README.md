# Daily Browser

Termux-এর কমান্ডগুলো বাটন হিসেবে চালানোর ওপেন-সোর্স Android অ্যাপ।

## সেটআপ
1. Termux (F-Droid) ইনস্টল করুন।
2. Termux-এ: `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties` — তারপর Termux রিস্টার্ট।
3. এই অ্যাপের অনুমতি দিন: "Run commands in Termux environment"।

## ব্যবহার
- বাটনে চাপলে কমান্ড Termux-এ চলে (অথবা ব্যাকগ্রাউন্ডে চলে আউটপুট অ্যাপে দেখায়)।
- ＋ দিয়ে নিজের কমান্ড যোগ, ✎ দিয়ে সম্পাদনা/মোছা।
- "🖥 কম্পিউটার মোড" বাটন: হালকা ডেস্কটপ (Openbox + Termux:X11) চালু করে। আগে Termux:X11 অ্যাপ ইনস্টল করুন (github.com/termux/termux-x11 Releases)।

APK: Releases পাতা থেকে নিন (প্রতি push-এ স্বয়ংক্রিয় বিল্ড)।
