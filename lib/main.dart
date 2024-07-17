import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const MyHomePage(title: 'Flutter Demo Home Page'),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});
  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  static const recordChannel = MethodChannel("recordPlatform");
  String url = "";
  bool stopped = false;
  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(
          backgroundColor: Theme.of(context).colorScheme.inversePrimary,
          title: Text(widget.title),
        ),
        body: SafeArea(
          child: Column(
            children: [
              ElevatedButton(
                  onPressed: () async {
                    try {
                      await recordChannel.invokeMethod('start');
                    } on PlatformException catch (e) {
                      debugPrint("Fail: '${e.message}'.");
                    }
                  },
                  child: const Text("Clock in"),),
              ElevatedButton(
                onPressed: () async {
                  try {
                    final String result =
                        await recordChannel.invokeMethod('stop');
                    setState(() {
                      stopped = true;
                      url = result;
                    });
                    debugPrint(result);
                  } on PlatformException catch (e) {
                    debugPrint("Fail: '${e.message}'.");
                  }
                },
                child: const Text("Clock out"),
              ),
            ],
          ),
        ));
  }
}
